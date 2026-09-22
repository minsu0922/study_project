/* =====================================================================
 * csquiz 공용 스크립트 — API 호출 / 토큰 보관
 * ---------------------------------------------------------------------
 * 모든 페이지가 이 파일을 <먼저> 불러온다. 역할 세 가지:
 *   1) api()      : 백엔드 호출을 한 곳으로 통일 (토큰 부착 + 공통 봉투 해석)
 *   2) 토큰 보관   : localStorage에 저장 (아래 "왜 localStorage인가" 참고)
 *   3) 라벨/포맷   : 분야·난이도·유형 이름표와 날짜 표기
 *
 * 화면을 그리는 일은 <b>여기 없다</b> — js/shell.js가 맡는다(2026-09-06에 분리).
 * 이 파일은 화면이 있는지도 모르는 채로 동작해야 한다. 그래야 관리 콘솔처럼
 * 셸이 다른 곳에서도 그대로 쓰이고, 인증을 고치러 들어와 메뉴 배열을 지나치는
 * 일이 없다. 자세한 이유는 shell.js 상단 주석에 있다.
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
// 내비게이션에 "누구로 로그인했는지" 표시용. V12에서 이메일 → 아이디로 바뀌었다.
// 키 이름까지 바꾼 이유: 옛 키에 이메일이 남아 있으면 로그인하지 않은 화면에
// 옛 주소가 그대로 떠 있게 된다(값의 뜻이 달라졌으니 그릇도 새로 쓴다).
const USERNAME_KEY = "csquiz_username";

/* ── 토큰 보관 ── */
function getToken() { return localStorage.getItem(TOKEN_KEY); }
function setLogin(accessToken, refreshToken, username) {
  localStorage.setItem(TOKEN_KEY, accessToken);
  if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken); // Redis 장애 시 null일 수 있음
  localStorage.setItem(USERNAME_KEY, username);
}
function clearLogin() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_KEY);
  localStorage.removeItem(USERNAME_KEY);
  localStorage.removeItem("csquiz_email"); // V12 이전 키의 잔재 청소
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
 * (셸의 복습 배지 + 페이지 본론 API). 둘 다 401을 받고 각자 재발급을
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

/* ── enum 표시용 상수 (백엔드 global/common enum과 1:1) ──
 *
 * [분야(DOMAINS)만 서버에서 다시 채운다 — 8번 작업]
 * 예전에는 이 배열이 자바 enum과 완전히 따로 있어서, 분야 이름 하나를 고치려면
 * api.js와 Domain.java 두 곳을 같이 고쳐야 했다. 이제는 GET /api/domains가
 * DomainSetting 테이블(관리자가 9번 작업 화면에서 고치는 값)을 그대로 내려 준다.
 *
 * 그런데도 아래 11개 쌍을 <지우지 않고> 그대로 남겨 뒀다. 이유는 하나다 —
 * 이 배열을 쓰는 자리가 이 파일 하나가 아니라 admin/documents.html·admin/generate.html·
 * admin/problems.html·documents.html·quiz.html·wrong-answers.html 등 정적 페이지
 * 여러 곳에 fillSelect(el, DOMAINS, ...) 꼴로 흩어져 있다. 그 호출을 전부 비동기로
 * 바꾸면(await fillSelect 같은 식) 페이지마다 스크립트 흐름을 다시 짜야 하고, 한 곳만
 * 빠뜨리면 그 화면은 domainLabel이 원본 상수명(BACKEND_FRAMEWORK 같은)을 그대로
 * 돌려줘 학습자에게 날것 그대로 보인다. 그래서 DOMAINS는 <언제나 즉시 쓸 수 있는
 * 동기 배열>로 남기고, 실제 값은 아래 fetchDomains()가 배열의 <내용만> 서버 응답으로
 * 갈아 끼운다(재대입이 아니라 splice — 재대입하면 이 상수를 미리 들고 있던 다른 클로저는
 * 옛 배열을 계속 보게 된다). 요청이 실패하거나 늦어도 이 폴백 11개가 그대로 남아 있어
 * 화면은 항상 오늘과 똑같이 동작한다 — 이 배열은 "나중에 지울 중복"이 아니라
 * <요청이 실패했을 때 화면을 지켜 주는 안전망>이다. */
const DOMAINS = [
  ["NETWORK", "네트워크"], ["OS", "운영체제"], ["DATABASE", "데이터베이스"],
  ["DS_ALGORITHM", "자료구조·알고리즘"], ["SYSTEM_DESIGN", "시스템설계"],
  ["SOFTWARE_ENGINEERING", "소프트웨어공학"], ["SECURITY", "보안"],
  ["LANGUAGE_RUNTIME", "언어·런타임"], ["BACKEND_FRAMEWORK", "스프링·백엔드"], ["CLOUD_INFRA", "클라우드·인프라"],
  ["INTEGRATED", "통합시나리오"],
];

/**
 * DOMAINS를 GET /api/domains 응답으로 <제자리에서> 바꿔 채운다.
 *
 * splice(0, 길이, ...새값)를 쓰는 이유는 위 DOMAINS 주석과 같다 — `DOMAINS = [...]`처럼
 * 재대입하면 이 시점 이전에 `const arr = DOMAINS`로 참조를 들고 있던 코드는 새 값을
 * 못 본다. 배열 하나를 모두가 공유하는 그릇으로 계속 써야, 부르는 순서를 안 가려도 된다.
 *
 * 실패를 조용히 삼킨다 — 이 함수가 던지면 각 페이지가 전부 try/catch를 달아야 하는데,
 * 그렇게까지 해서 지킬 값이 아니다(실패하면 폴백 11개가 이미 화면과 정확히 같다).
 */
async function fetchDomains() {
  try {
    const res = await api("/api/domains");
    // 빈 응답이면 갈아 끼우지 않는다(최종 리뷰 Minor 1). 첫 배포 때처럼 설정 행이 아직 없는
    // 순간 — Tomcat이 요청을 받기 시작한 뒤 동기화 러너(@Order 4)가 행을 만들기 전 — 에는
    // 서버가 성공(200)으로 빈 배열을 준다. 그대로 splice하면 폴백 11개가 지워져 학습자 화면의
    // 분야 필터가 텅 빈다. 분야가 0개인 상태는 정상적으로 존재하지 않으므로(enum 전체에 행이
    // 맞춰진다) 빈 배열은 "아직 모른다"로 읽고, 실패했을 때와 똑같이 폴백을 남긴다.
    if (!Array.isArray(res) || res.length === 0) return;
    DOMAINS.splice(0, DOMAINS.length, ...res.map(d => [d.code, d.displayName]));
  } catch (e) {
    // 네트워크 오류·서버 오류 모두 여기로 온다. DOMAINS는 폴백 값 그대로 남으므로
    // 화면은 오늘까지의 동작과 다르지 않다 — 그래서 로그조차 남기지 않는다.
  }
}

/**
 * "DOMAINS가 서버 값으로 채워졌다"를 기다릴 자리를 위한 약속(Promise).
 *
 * 대부분의 화면은 이것을 <기다리지 않는다> — 위 DOMAINS 주석대로 이미 옳은 폴백을
 * 들고 있어 기다릴 이유가 없고, 기다리면 그만큼 첫 화면이 늦게 뜬다. 다만 분야 필터가
 * <그 화면이 열리자마자 보여 줄 유일한 것>인 학습자 화면(documents.html·quiz.html·
 * wrong-answers.html)은 다르다 — 관리자가 이름을 고친 지 얼마 안 된 시점에 그 화면을
 * 열면, 기다리지 않을 경우 새로고침 없이는 옛 이름이 계속 보인다(아래 이유: fillSelect가
 * <option>을 그 순간의 배열 내용으로 한 번만 굽기 때문에, 나중에 splice로 배열을 고쳐도
 * 이미 그려진 <select>는 저절로 다시 그려지지 않는다). 관리자 화면(admin/**)은 그대로
 * 기다리지 않는다 — 같은 사람이 분야 설정 화면(9번 작업)에서 직접 최신 값을 보고 있으니
 * 여기서 한 박자 늦는 것이 학습자 화면만큼 아프지 않고, 관리 화면은 손댈 곳이 이미 많아
 * 필요 이상으로 흐름을 바꾸면 그만큼 회귀 위험만 커진다.
 */
const domainsReady = fetchDomains();

const DIFFICULTIES = [["BEGINNER", "초급"], ["INTERMEDIATE", "중급"], ["ADVANCED", "고급"]];
// ESSAY는 자동채점 미지원이라 화면에서도 제외한다(ProblemType.isAutoScored와 같은 기준).
const TYPES = [
  ["MULTIPLE_CHOICE", "객관식"], ["OX", "OX"], ["SHORT_ANSWER", "단답형"],
  ["MATCHING", "짝짓기"], ["ORDERING", "순서 배열"],
];

function domainLabel(v) { const f = DOMAINS.find(d => d[0] === v); return f ? f[1] : v; }
function difficultyLabel(v) { const f = DIFFICULTIES.find(d => d[0] === v); return f ? f[1] : v; }
function typeLabel(v) { const f = TYPES.find(d => d[0] === v); return f ? f[1] : v; }

/**
 * 난이도 배지 HTML — 색과 글자를 <한 곳에서> 만든다(2026-08-29 개편 3단계).
 *
 * 난이도가 그려지는 자리는 지금 다섯 곳이다(퀴즈 풀이·오답노트·문제 목록·관리 검수·
 * 관리 문제 목록). 각자 클래스를 손으로 붙이면 난이도가 하나 늘거나 색이 바뀔 때
 * 다섯 곳을 같이 고쳐야 하고, 언젠가 한 곳만 남는다.
 *
 * 색은 DIFFICULTIES의 <순서>에서 나온다 — 그 배열이 이미 초·중·고 오름차순이라
 * 별도의 매핑 표를 두면 두 곳이 어긋날 자리가 생긴다.
 */
function difficultyBadge(v) {
  const i = DIFFICULTIES.findIndex(d => d[0] === v);
  const cls = i >= 0 ? `lv${i + 1}` : "gray";   // 모르는 값은 회색 — 색을 지어내지 않는다
  return `<span class="badge ${cls}">${escapeHtml(difficultyLabel(v))}</span>`;
}

/**
 * 이 편이 심화편인가 — 색을 가르는 <유일한> 판정(2026-09-04).
 *
 * 서버는 편을 표시용 한국어("입문편"/"심화편")로 내려준다(DocumentEdition.getDisplayName).
 * 그래서 화면은 그 낱말을 비교해 색을 고르는데, 그 비교가 두 곳에 생기면 언젠가 한쪽만
 * 남는다 — difficultyBadge를 한 곳으로 모은 것과 같은 이유다.
 *
 * 값이 없으면(짝이 없는 한 편짜리 문서) 서버가 edition을 비워 보낸다. 그때는 배지 자체를
 * 그리지 않으므로 여기서도 그냥 false다.
 */
function isAdvancedEdition(edition) {
  return edition === "심화편";
}

/**
 * 편 배지 HTML — 짝이 없으면 <빈 문자열>이라 마크업 자체가 생기지 않는다.
 *
 * 색은 난이도 배지와 같은 lv1/lv3을 쓴다. 입문편이 초급과, 심화편이 고급과 같은 색인 것은
 * 우연이 아니라 의도다 — 출제 배치가 실제로 그렇게 갈라 캔다(초·중급은 입문편, 고급은 심화편).
 */
function editionBadge(edition) {
  if (!edition) return "";
  const cls = isAdvancedEdition(edition) ? "edition advanced" : "edition";
  return `<span class="badge ${cls}">${escapeHtml(edition)}</span>`;
}

/** 심화편 slug의 꼬리 — 서버의 DocumentEditions.ADVANCED_SUFFIX와 같아야 한다. */
const ADVANCED_SLUG_SUFFIX = "-advanced";

/**
 * slug만 손에 쥐었을 때 붙일 편 이름 — 심화편이면 "심화편", 아니면 <빈 문자열>(2026-09-05).
 *
 * 왜 editionBadge를 못 쓰나. 두 가지가 다르다.
 *
 * 1) 쓰는 자리가 <option>과 <code>다. <option> 안에서는 HTML이 마크업으로 그려지지 않고
 *    태그가 글자로 보인다. 그래서 배지가 아니라 <글>이어야 한다.
 * 2) 손에 있는 것이 edition이 아니라 slug다. 근거 문서를 보여 주는 자리들
 *    (검수 목록 필터·문제 목록 필터·배치 현황)은 서버에서 slug 문자열만 받는다.
 *    편을 서버가 계산해 주는 곳은 문서 API뿐인데, 그쪽은 짝의 존재까지 DB로 확인한다.
 *
 * <b>입문편은 일부러 비워 둔다.</b> 꼬리가 없다고 입문편인 것이 아니다 — 2026-09-03 이전
 * 문서 15편은 두 편으로 갈리기 전의 <한 편짜리>라 꼬리가 없다. 거기에 "입문편"이라 적으면
 * 읽는 사람이 없는 심화편을 찾아 나선다. 서버가 "짝이 있을 때만 편을 붙인다"고 정한 것과
 * 같은 판단이다(DocumentEditions 클래스 주석). 반대로 -advanced 꼬리는 심화편 생성기만
 * 만들므로, 그 꼬리가 붙었다면 심화편인 것은 <확실하다>. 아는 것만 말한다.
 */
function editionOfSlug(slug) {
  return typeof slug === "string" && slug.endsWith(ADVANCED_SLUG_SUFFIX) ? "심화편" : "";
}

/**
 * 근거 문서 slug를 화면에 적을 때 쓰는 한 줄 — 심화편이면 뒤에 "(심화편)"을 붙인다.
 *
 * 두 편은 <제목이 완전히 같고> slug만 꼬리로 갈린다. 그래서 slug를 그대로 찍으면
 * 사람이 줄 끝의 -advanced를 눈으로 찾아내야 한다. 읽는 사람이 글자를 세게 하지 않는다.
 */
function slugWithEdition(slug) {
  const edition = editionOfSlug(slug);
  return edition ? `${escapeHtml(slug)} (${edition})` : escapeHtml(slug);
}

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
 * "2026-09-07T18:44:02" 또는 "2026-09-07" → <b>"9월 7일"</b>.
 *
 * <p>목록에서 연도와 분 단위는 정보가 아니라 소음이다. 이 앱의 문서·문제는 전부 올해 것이고,
 * "18:44에 올라왔다"가 읽는 사람의 판단을 바꾸지 않는다. 바꾸는 것은 <b>얼마나 최근인가</b>뿐이다.
 *
 * <p>첫 화면이 쓰던 것을 여기로 올렸다(2026-09-08). 같은 형식을 문서 목록도 쓰게 되면서
 * 화면마다 한 벌씩 두면 언젠가 한쪽만 고쳐진다 — 실제로 문서 목록은 그 사이
 * {@code formatDate}(분까지 찍는 쪽)를 쓰고 있어서 두 화면이 같은 날짜를 다르게 적고 있었다.
 *
 * <p>"오늘"·"어제" 같은 상대 표기는 여기서 하지 않는다. 그건 <b>화면마다 뜻이 다르다</b> —
 * 첫 화면은 "오늘 새 문서가 왔다"가 소식이라 그렇게 적을 값이 있지만, 목록에서는
 * 열 줄 중 하나만 "오늘"이면 정렬 기준이 뒤섞여 보인다. 판단은 부르는 쪽이 한다.
 */
function shortDay(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
  return m ? `${Number(m[2])}월 ${Number(m[3])}일` : (iso || "");
}

/* 내비게이션·권한 판정·로그인 표시는 js/shell.js로 옮겼다(2026-09-06 화면 개편).
   이 파일은 <토큰 보관·HTTP 호출·응답 봉투 해석>과 라벨/포맷 헬퍼만 맡는다.
   의존 방향은 shell.js → api.js 한쪽이다 — 이 파일이 화면을 아는 순간
   화면 없이 쓰는 곳(관리 콘솔·테스트)이 셸을 함께 끌고 오게 된다. */
