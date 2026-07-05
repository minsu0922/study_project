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
const EMAIL_KEY = "csquiz_email"; // 내비게이션에 "누구로 로그인했는지" 표시용

/* ── 토큰 보관 ── */
function getToken() { return localStorage.getItem(TOKEN_KEY); }
function setLogin(token, email) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(EMAIL_KEY, email);
}
function clearLogin() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(EMAIL_KEY);
}
function isLoggedIn() { return !!getToken(); }

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

  if (res.status === 401) {
    clearLogin(); // 만료/무효 토큰은 즉시 폐기 (renderNav를 다시 그리진 않음 — 페이지가 판단)
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
 * @param active 현재 페이지 키("docs"|"quiz"|"wrong") — 해당 메뉴를 강조
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
    <a class="${cls("docs")}" href="/">문서</a>
    <a class="${cls("quiz")}" href="/quiz.html">퀴즈</a>
    <a class="${cls("wrong")}" href="/wrong-answers.html">오답노트</a>
    <span class="spacer"></span>
    ${authArea}`;
  const logout = document.getElementById("logoutLink");
  if (logout) logout.addEventListener("click", e => {
    e.preventDefault();
    clearLogin();
    location.href = "/"; // 로그아웃 후 첫 화면으로
  });
}
