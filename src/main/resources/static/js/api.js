/* =====================================================================
 * csquiz 공용 스크립트 — API 호출 / 토큰 보관 / 내비게이션
 * ---------------------------------------------------------------------
 * 모든 페이지가 이 파일을 먼저 불러온다. 역할 세 가지:
 *   1) api()      : 백엔드 호출을 한 곳으로 통일 (토큰 부착 + 공통 봉투 해석)
 *   2) 토큰 보관   : localStorage에 저장 (아래 "왜 localStorage인가" 참고)
 *   3) renderNav(): 모든 페이지 공통 상단 메뉴를 그린다 (로그인 상태 반영)
 *
 * [왜 localStorage인가]
 * 브라우저에 토큰을 두는 곳은 크게 localStorage vs 쿠키(HttpOnly) 두 가지다.
 * - localStorage: 구현이 단순하고 JS로 꺼내 Authorization 헤더에 실어 보낸다.
 *   단점: XSS(악성 스크립트 주입)에 뚫리면 토큰을 읽힐 수 있다.
 * - HttpOnly 쿠키: JS가 못 읽어 XSS에 강하지만, CSRF 방어가 다시 필요해지고
 *   백엔드 설계(지금은 Authorization 헤더 기반)를 바꿔야 한다.
 * MVP는 학습용 로컬 사이트라 단순한 localStorage를 쓰고, 우리가 렌더링하는
 * 모든 외부 텍스트를 escapeHtml()로 이스케이프해 XSS 자체를 막는다.
 * (보안 강화는 로드맵 — refresh 토큰 도입 시 재검토)
 * ===================================================================== */

const TOKEN_KEY = "csquiz_token";
const REFRESH_KEY = "csquiz_refresh"; // 로드맵 2: access 만료 시 재발급용
const EMAIL_KEY = "csquiz_email"; // 내비게이션에 "누구로 로그인했는지" 표시용

/* ── 토큰 보관 ── */
function getToken() { return localStorage.getItem(TOKEN_KEY); }
function setLogin(accessToken, refreshToken, email) {
  localStorage.setItem(TOKEN_KEY, accessToken);
  if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken); // Redis 장애 시 null일 수 있음
  localStorage.setItem(EMAIL_KEY, email);
}
function clearLogin() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_KEY);
  localStorage.removeItem(EMAIL_KEY);
}
function isLoggedIn() { return !!getToken(); }

/**
 * JWT payload에서 role(USER/ADMIN)을 읽는다 — 관리자 메뉴 표시 여부 판단용.
 *
 * <p>원리: JWT의 가운데 조각(payload)은 암호화가 아니라 base64url <b>인코딩</b>이라
 * 브라우저에서 그냥 풀어 읽을 수 있다(문서 session-vs-jwt 참고).
 * <b>이 값은 UI 편의용일 뿐 보안 장치가 아니다</b> — 값을 조작해 관리자 메뉴를 띄워도
 * 서버(SecurityConfig의 hasRole)가 서명된 토큰의 role로 다시 검사하므로 API는 뚫리지 않는다.
 * "화면은 속일 수 있어도 서버는 못 속인다"가 권한 설계의 기본 전제다.
 */
function getRole() {
  const token = getToken();
  if (!token) return null;
  try {
    // base64url → base64 변환(-→+, _→/) 후 디코딩
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return payload.role || null;
  } catch (e) {
    return null; // 형식이 깨진 토큰은 비로그인 취급
  }
}
function isAdmin() { return getRole() === "ADMIN"; }

/**
 * 백엔드 API 호출 공통 함수.
 * - 토큰이 있으면 Authorization: Bearer 헤더를 자동으로 붙인다.
 * - 응답 봉투 { success, data, error }를 해석해서, 성공이면 data만 돌려주고
 *   실패면 Error를 던진다(err.code / err.message / err.fieldErrors 사용 가능).
 *   → 각 페이지는 try/catch 한 번으로 성공·실패를 처리하면 된다.
 * - 401(토큰 만료 등)이면 저장된 토큰을 지운다. 1시간짜리 access 토큰이
 *   만료된 채 남아 있으면 "로그인했는데 계속 실패"하는 혼란이 생기기 때문.
 */
async function api(path, options = {}) {
  const headers = Object.assign({}, options.headers);
  if (options.body) headers["Content-Type"] = "application/json";
  const token = getToken();
  if (token) headers["Authorization"] = "Bearer " + token;

  const res = await fetch(path, Object.assign({}, options, { headers }));

  let body = null;
  try { body = await res.json(); } catch (e) { /* 본문 없는 응답(이론상 없음) */ }

  // 로드맵 2: access 만료(401) → refresh 토큰으로 조용히 재발급 후 원래 요청을 1번 재시도.
  // 사용자는 1시간마다 로그아웃당하는 대신 아무것도 못 느낀다. _retried 플래그로
  // 무한 재시도를 막고, 인증 API 자신(로그인/재발급)의 401은 재시도 대상이 아니다.
  if (res.status === 401 && !options._retried && !path.startsWith("/api/auth/")) {
    if (await tryRefresh()) {
      return api(path, Object.assign({}, options, { _retried: true }));
    }
    clearLogin(); // 재발급도 실패 = 진짜 세션 종료 → 재로그인 필요
  }
  if (!res.ok || !body || body.success === false) {
    const errInfo = (body && body.error) || { code: "HTTP_" + res.status, message: "요청에 실패했습니다." };
    const err = new Error(errInfo.message);
    err.code = errInfo.code;
    err.status = res.status;
    err.fieldErrors = errInfo.fieldErrors || [];
    throw err;
  }
  return body.data;
}

/**
 * 진행 중인 재발급 Promise(single-flight 공유용). null이면 재발급 중이 아님.
 *
 * [왜 필요한가 — 동시 401 경쟁 상태]
 * access 토큰이 만료된 채 페이지를 열면 요청이 동시에 여러 개 나간다
 * (renderNav의 복습 배지 + 페이지 본론 API). 둘 다 401을 받고 각자 재발급을
 * 시도하는데, 서버는 보안상 refresh 토큰 "회전"(한 번 쓴 토큰은 즉시 폐기,
 * 재사용은 AUTH_005로 거부 — 탈취 감지 장치)을 하므로 두 번째 재발급은
 * 반드시 실패한다. 그 실패가 api()의 clearLogin()으로 이어져 첫 번째가
 * 방금 받아둔 멀쩡한 새 토큰까지 지워 버린다 → 영문 모를 로그아웃.
 *
 * [해결 — single-flight]
 * 재발급을 "1개만 띄우고 나머지는 그 결과를 같이 기다리게" 한다.
 * 먼저 도착한 호출이 Promise를 만들어 이 변수에 걸어두면, 그 사이에 온
 * 호출들은 새 fetch를 만들지 않고 같은 Promise를 돌려받는다.
 * (JS는 단일 스레드라 "확인 후 대입" 사이에 다른 코드가 끼어들 수 없어
 * 이 패턴만으로 안전하다 — 서버였다면 락이 필요했을 일.)
 * 서버의 회전 정책은 의도된 보안 설계이므로 건드리지 않고 클라이언트만 고친다.
 */
let refreshPromise = null;

/**
 * refresh 토큰으로 access 재발급 시도(single-flight 입구). 성공 시 true.
 * 실제 네트워크 호출은 doRefresh()에 있고, 여기서는 "이미 진행 중이면
 * 그 Promise를 재사용"하는 교통정리만 한다.
 */
function tryRefresh() {
  if (refreshPromise) return refreshPromise; // 이미 누가 재발급 중 → 결과만 같이 기다린다
  // finally로 반드시 비워야 다음 만료 때(1시간 뒤) 새 재발급을 띄울 수 있다.
  // 실패 결과를 계속 물고 있으면 재로그인 후에도 영영 재발급이 안 되는 버그가 된다.
  refreshPromise = doRefresh().finally(() => { refreshPromise = null; });
  return refreshPromise;
}

/** 재발급 실제 수행. 성공 시 새 토큰 쌍 저장(회전) 후 true. tryRefresh()를 통해서만 호출할 것. */
async function doRefresh() {
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return false;
  try {
    const res = await fetch("/api/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
    const body = await res.json();
    if (!res.ok || !body.success) return false; // 만료·이미 사용(AUTH_005) → 재로그인 필요
    localStorage.setItem(TOKEN_KEY, body.data.accessToken);
    if (body.data.refreshToken) localStorage.setItem(REFRESH_KEY, body.data.refreshToken);
    return true;
  } catch (e) {
    return false; // 네트워크 오류 등 — 호출부가 로그인 만료로 처리
  }
}

/* ── enum 표시용 상수 (백엔드 global/common enum과 1:1, 서버가 진실의 원천) ── */
const DOMAINS = [
  ["NETWORK", "네트워크"], ["OS", "운영체제"], ["DATABASE", "데이터베이스"],
  ["DS_ALGORITHM", "자료구조·알고리즘"], ["SYSTEM_DESIGN", "시스템설계"], ["SECURITY", "보안"],
  ["LANGUAGE_RUNTIME", "언어·런타임"], ["CLOUD_INFRA", "클라우드·인프라"],
  ["FRONTEND_CS", "프론트엔드CS"], ["INTEGRATED", "통합시나리오"],
];
const DIFFICULTIES = [["BEGINNER", "초급"], ["INTERMEDIATE", "중급"], ["ADVANCED", "고급"]];
const TYPES = [["MULTIPLE_CHOICE", "객관식"], ["OX", "OX"], ["SHORT_ANSWER", "단답형"]]; // ESSAY는 MVP 제외

function domainLabel(v) { const f = DOMAINS.find(d => d[0] === v); return f ? f[1] : v; }
function difficultyLabel(v) { const f = DIFFICULTIES.find(d => d[0] === v); return f ? f[1] : v; }
function typeLabel(v) { const f = TYPES.find(d => d[0] === v); return f ? f[1] : v; }

/** <select>에 "전체" + enum 옵션을 채운다 (목록 필터 공용) */
function fillSelect(selectEl, pairs, allLabel) {
  selectEl.innerHTML = "";
  if (allLabel) selectEl.append(new Option(allLabel, ""));
  pairs.forEach(([value, label]) => selectEl.append(new Option(label, value)));
}

/**
 * XSS 방지용 이스케이프 — 서버에서 온 텍스트(문서 제목, 문제 지문, 답 등)를
 * innerHTML에 넣기 전 반드시 이 함수를 거친다. (localStorage 토큰 방식의
 * 전제 조건: 파일 상단 주석 참고)
 */
function escapeHtml(s) {
  return String(s ?? "")
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

/** ISO 날짜 문자열 → "2026-07-05 10:06" 같은 짧은 표기 */
function formatDate(iso) {
  if (!iso) return "";
  return iso.replace("T", " ").substring(0, 16);
}

/**
 * 공통 상단 메뉴. 각 페이지의 <header id="nav">에 그린다.
 * @param active 현재 페이지 키("home"|"docs"|"quiz"|"review"|"wrong"|"admin") — 해당 메뉴를 강조
 *
 * v2 구조: 홈(/)이 문서 목록이 아니라 "시작 화면"이 됐다(퀴즈 사이트 리뉴얼).
 * 문서 목록은 /documents.html로 이동. 복습 메뉴에는 "오늘 몇 개"인지 배지가 붙는다
 * (아래 loadReviewBadge — 할 일이 남아 있음을 어느 페이지에서든 보이게 하는 장치).
 */
function renderNav(active) {
  const el = document.getElementById("nav");
  if (!el) return;
  const cls = k => (k === active ? "active" : "");
  const authArea = isLoggedIn()
    ? `<span class="user-email">${escapeHtml(localStorage.getItem(EMAIL_KEY) || "")}</span>
       <a href="#" id="logoutLink">로그아웃</a>`
    : `<a href="/login.html">로그인</a>
       <a href="/signup.html" class="btn btn-outline" style="padding:5px 14px">회원가입</a>`;
  el.className = "nav";
  el.innerHTML = `
    <a class="brand" href="/">csquiz</a>
    <a class="${cls("home")}" href="/">홈</a>
    <a class="${cls("quiz")}" href="/quiz.html">퀴즈</a>
    <a class="${cls("review")}" href="/review.html">복습<span id="reviewBadge"></span></a>
    <a class="${cls("wrong")}" href="/wrong-answers.html">오답노트</a>
    <a class="${cls("docs")}" href="/documents.html">문서</a>
    ${isAdmin() ? `<a class="${cls("admin")}" href="/admin.html">관리자</a>` : ""}
    <span class="spacer"></span>
    ${authArea}`;
  loadReviewBadge();
  const logout = document.getElementById("logoutLink");
  if (logout) logout.addEventListener("click", async e => {
    e.preventDefault();
    // 서버의 refresh 토큰을 먼저 폐기(로드맵 2) — 브라우저만 지우면 서버엔 14일짜리
    // 출입증이 살아 있는 셈이라, "로그아웃 = 서버에서도 회수"가 올바른 순서다.
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    if (refreshToken) {
      try {
        await fetch("/api/auth/logout", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
        });
      } catch (err) { /* 서버 폐기 실패해도 로컬 로그아웃은 진행(TTL이 안전망) */ }
    }
    clearLogin();
    location.href = "/"; // 로그아웃 후 첫 화면으로
  });
}

/**
 * 복습 메뉴 배지 — "오늘 복습할 문제 N개"를 메뉴에 작은 숫자로 표시한다.
 *
 * 이유: 간격 반복(로드맵 4)은 "때가 됐을 때 다시 보는 것"이 핵심이라, 사용자가
 * 복습 페이지에 일부러 들어가지 않아도 할 일이 있음을 어디서든 알 수 있어야 한다.
 * size=1로 요청하는 이유: 필요한 건 목록이 아니라 totalElements(개수)뿐이라
 * 본문 전송을 최소화한다. 실패는 조용히 무시 — 배지는 있으면 좋은 정보일 뿐,
 * 이것 때문에 페이지가 에러를 띄우면 주객전도다.
 */
async function loadReviewBadge() {
  if (!isLoggedIn()) return;
  try {
    const data = await api("/api/me/reviews/today?size=1");
    const el = document.getElementById("reviewBadge");
    if (el && data.totalElements > 0) {
      el.innerHTML = `<span class="nav-badge">${data.totalElements}</span>`;
    }
  } catch (e) { /* 배지 실패는 무시(위 주석) */ }
}
