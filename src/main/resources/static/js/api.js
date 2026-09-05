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
  ["DS_ALGORITHM", "자료구조·알고리즘"], ["SYSTEM_DESIGN", "시스템설계"],
  ["SOFTWARE_ENGINEERING", "소프트웨어공학"], ["SECURITY", "보안"],
  ["LANGUAGE_RUNTIME", "언어·런타임"], ["BACKEND_FRAMEWORK", "스프링·백엔드"], ["CLOUD_INFRA", "클라우드·인프라"],
  ["FRONTEND_CS", "프론트엔드CS"], ["INTEGRATED", "통합시나리오"],
];
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
 * 공통 상단 메뉴. 각 페이지의 <header id="nav">에 그린다.
 * @param active 현재 페이지 키("home"|"docs"|"quiz"|"review"|"wrong"|"admin") — 해당 메뉴를 강조
 *
 * v2 구조: 홈(/)이 문서 목록이 아니라 "시작 화면"이 됐다(퀴즈 사이트 리뉴얼).
 * 문서 목록은 /documents.html로 이동. 복습 메뉴에는 "오늘 몇 개"인지 배지가 붙는다
 * (아래 loadReviewBadge — 할 일이 남아 있음을 어느 페이지에서든 보이게 하는 장치).
 */
/* ── 권한 세 등급 ─────────────────────────────────────────────
 * 비로그인(anon) → 로그인 사용자(user) → 관리자(admin). 넓은 쪽이 좁은 쪽을 포함한다 —
 * 관리자도 학습자이므로 사용자 화면을 그대로 쓴다(관리 콘솔만 따로 있다).
 *
 * [이건 UX이지 보안이 아니다] 메뉴를 감추는 것은 "누를 곳을 줄여 주는 것"이지 막는 것이
 * 아니다. JWT payload는 조작할 수 있으므로 아래 판정은 전부 장식이다. 진짜 방어는 서버
 * 두 겹이다 — API는 SecurityConfig의 hasRole, 관리 화면 파일은 AdminGateFilter의 쿠키.
 * 그래서 이 표가 틀려도 데이터는 새지 않는다. 반대로 서버만 있고 이 표가 없으면
 * "눌렀더니 401"이 반복돼 화면이 고장 난 것처럼 보인다. */
const ROLE_RANK = { public: 0, user: 1, admin: 2 };

/** 지금 이 브라우저의 등급. */
function currentRole() {
  if (isAdmin()) return "admin";
  if (isLoggedIn()) return "user";
  return "anon";
}

/** need("public"|"user"|"admin") 이상의 권한을 가졌는가. 페이지 가드도 이걸 쓰면 된다. */
function hasRole(need) {
  const have = { anon: 0, user: 1, admin: 2 }[currentRole()];
  return have >= (ROLE_RANK[need] ?? 0);
}

/**
 * 메뉴 선언 — <b>"이 메뉴는 어느 권한이 필요한가"를 적어 두는 유일한 곳</b>.
 *
 * 예전에는 renderNav 안에 링크가 하드코딩돼 있었고 관리자 링크만 조건부였다. 그래서
 * 비로그인 방문자에게도 복습·오답노트가 보였고, 누르면 그제야 "로그인하세요"가 떴다.
 * 권한이 셋이 된 지금 그 방식은 조건문이 링크 수만큼 흩어진다는 뜻이라, 표로 옮긴다.
 * 메뉴가 늘어도 여기 한 줄만 추가하면 내비게이션이 알아서 걸러 준다.
 *
 * 메뉴 이름은 기능명이 아니라 "언제 누르는지"가 드러나게 짓는다(UX 1단계 개편) —
 * "문서"는 무엇의 문서인지 모호해 "개념 문서"로 바꿨다.
 *
 * <h2>여섯에서 넷으로 (2026-08-29)</h2>
 *
 * <p>메뉴를 코드로 훑어 보니 셋이 어긋나 있었다.
 * <ul>
 *   <li><b>오늘의 퀴즈가 메뉴에 없었다.</b> 이 앱이 내건 약속은 "매일 조금씩"이고 매일 하는
 *       행동이 데일리인데, 거기 가려면 홈의 히어로 버튼이나 자유 퀴즈 안내문의 링크를
 *       찾아야 했다. 가장 중요한 행동이 메뉴에 없는 상태였다.
 *   <li><b>"자유 퀴즈"와 "문제"가 같은 일을 했다.</b> 둘 다 풀 것을 고르는 자리다 — 한쪽은
 *       조건을 걸고 무작위 열 문제, 다른 쪽은 목록에서 하나. 이름만 봐서는 어디로 들어가야
 *       하는지 안 갈린다. 자유 퀴즈는 문제 목록 안의 <b>"무작위로 10문제"</b> 버튼이 됐다.
 *   <li><b>"복습"과 "오답노트"도 한 쌍이었다.</b> 복습 화면이 아예 "내 답·해설을 다시 읽고
 *       싶으면 → 오답노트"라고 안내한다 — 화면이 스스로 자기가 반쪽이라고 말한 셈이다.
 *       둘을 한 구역으로 묶고 화면 안 탭으로 오간다.
 * </ul>
 *
 * <p>홈이 <b>"오늘"</b>이 됐다. 첫 화면이 광고가 아니라 오늘 할 일이 되므로 이름도 그것을
 * 가리켜야 한다. 데일리 화면(daily.html)도 이 메뉴에 걸린다 — 같은 구역이다.
 *
 * <p>"개념 문서"는 줄이지 않았다. 시안에서는 "개념"으로 짧게 뒀지만, 위 주석에 적힌
 * 과거 판단("문서만으로는 무엇의 문서인지 모호하다")이 여전히 맞다. 넷이면 자리가 넉넉하다.
 */
const MENUS = [
  { key: "today", label: "오늘", href: "/", need: "public" },
  // 문제 목록은 내 풀이 기록을 함께 보여 주는 화면이라 로그인 사용자에게만 띄운다(docs/18)
  { key: "problems", label: "문제", href: "/problems.html", need: "user" },
  // 배지는 "오늘 복습할 게 남았다"를 어느 화면에서든 보이게 하는 장치(loadReviewBadge)
  { key: "review", label: "복습", href: "/review.html", need: "user", badge: "reviewBadge" },
  { key: "docs", label: "개념 문서", href: "/documents.html", need: "public" },
  // 관리 콘솔은 "다른 영역으로 나간다"는 뜻이라 화살표를 붙여 다른 메뉴와 구분한다
  { key: "admin", label: "관리 콘솔 ↗", href: "/admin/index.html", need: "admin" },
];

/**
 * 사용자 화면 상단 내비게이션 — 권한에 맞는 메뉴만 그린다.
 *
 * v2 구조: 홈(/)이 문서 목록이 아니라 "시작 화면"이 됐다(퀴즈 사이트 리뉴얼).
 * 문서 목록은 /documents.html로 이동.
 */
function renderNav(active) {
  const el = document.getElementById("nav");
  if (!el) return;
  el.className = "nav";
  el.innerHTML = `
    <a class="brand" href="/">csquiz</a>
    ${MENUS.filter(m => hasRole(m.need)).map(m =>
      `<a class="${m.key === active ? "active" : ""}" href="${m.href}">${m.label}` +
      `${m.badge ? `<span id="${m.badge}"></span>` : ""}</a>`).join("")}
    <span class="spacer"></span>
    ${authAreaHtml()}`;
  loadReviewBadge();
  wireLogout();
}

/** 로그인 상태 표시 영역 — 사용자 화면과 관리 콘솔이 함께 쓴다. */
function authAreaHtml() {
  return isLoggedIn()
    ? `<span class="user-email">${escapeHtml(localStorage.getItem(USERNAME_KEY) || "")}</span>
       <a href="#" id="logoutLink">로그아웃</a>`
    : `<a href="/login.html">로그인</a>
       <a href="/signup.html" class="btn btn-outline" style="padding:5px 14px">회원가입</a>`;
}

/**
 * 로그아웃 링크에 동작을 붙인다 — <b>두 내비게이션이 같은 함수를 쓴다</b>.
 *
 * 복사해 두면 안 되는 코드다: 서버의 refresh 토큰 폐기가 빠진 사본이 생기면
 * "로그아웃했는데 서버에는 14일짜리 출입증이 살아 있는" 상태가 그쪽 화면에서만 생긴다.
 */
function wireLogout() {
  const logout = document.getElementById("logoutLink");
  if (!logout) return;
  logout.addEventListener("click", async e => {
    e.preventDefault();
    // 서버의 refresh 토큰을 먼저 폐기(로드맵 2) — 브라우저만 지우면 서버엔 14일짜리
    // 출입증이 살아 있는 셈이라, "로그아웃 = 서버에서도 회수"가 올바른 순서다.
    // 관리 화면 출입증 쿠키도 이 응답에서 함께 지워진다(AdminGateCookie).
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
