/* =====================================================================
 * shell.js — 모든 화면을 감싸는 껍데기(내비게이션·권한 판정·로그인 표시)
 * ---------------------------------------------------------------------
 * [왜 api.js에서 떼어냈나 · 2026-09-06 화면 개편]
 * api.js가 하는 일은 토큰 보관·HTTP 호출·응답 봉투 해석이다. 거기에 화면을 그리는
 * 코드가 얹혀 430줄이 됐고, "토큰 만료를 고치러 들어갔다가 메뉴 배열을 지나치는"
 * 상태가 됐다. 두 파일은 <바뀌는 이유>가 다르다 — 메뉴가 하나 늘 때 인증 코드를
 * 다시 읽을 이유가 없고, 토큰 재발급을 손볼 때 메뉴 라벨을 볼 이유도 없다.
 *
 * 곧 셸이 상단 가로바에서 사이드바로 바뀌는데, 그 마크업은 지금보다 크고 본문을
 * 감싼다. 그 코드가 들어올 자리를 먼저 만들어 두는 것이기도 하다.
 *
 * [의존 방향은 한쪽이다] shell.js → api.js. 반대는 없다.
 * api.js는 <화면이 있는지도 모르는 채로> 동작해야 한다. 관리 콘솔도 api.js를 쓰고,
 * 나중에 화면 없이 부를 일이 생겨도 그쪽이 끌려오지 않는다.
 *
 * [로드 순서가 곧 의존성이다] HTML에서 api.js 다음에 온다. 번들러가 없어 전역
 * 함수로 이어 붙이는 구조라, 순서를 사람이 지켜야 한다. 사람이 지키는 규칙은
 * 언젠가 깨지므로 StaticPageStructureTest가 대신 지킨다.
 *
 * [관리 콘솔도 이 파일을 쓴다] authAreaHtml·wireLogout을 admin-common.js가
 * 그대로 부른다. 복사해 두면 서버 토큰 폐기가 빠진 사본이 생겨, 콘솔에서
 * 로그아웃했을 때만 서버에 출입증이 남는 상태가 된다.
 * ===================================================================== */

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
 * 예전에는 상단 가로바를 그리는 함수 안에 링크가 하드코딩돼 있었고 관리자 링크만 조건부였다. 그래서
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
 *
 * <h2>넷에서 여섯으로, 그리고 탭 다섯 (2026-09-06)</h2>
 *
 * <p>내 기록·설정이 생기면서 항목이 여섯이 됐다. 사이드바에는 여섯이 이름째로 들어가지만
 * <b>폰 탭바는 다섯이 상한</b>이다 — 375px에서 여섯을 넣으면 "개념 문서"가 두 줄이 되거나
 * 잘린다. 그래서 {@code tab}이 있는 것만 탭바에 오르고, 설정은 탭에서 빠져 내 기록 화면
 * 안의 입구로 들어간다("나" 탭 하나가 둘을 대표한다).
 *
 * <p><b>관리 콘솔에 tab이 없는 이유</b>는 자리가 모자라서가 아니다. 콘솔은 다른 영역이라
 * 학습하다 잘못 눌러 넘어가면 안 된다 — admin-common.js가 같은 이유로 콘솔 쪽에
 * 학습 메뉴를 두지 않는다.
 */
const MENUS = [
  { key: "today", label: "오늘", href: "/", need: "public", tab: "☀️" },
  // 문제 목록은 내 풀이 기록을 함께 보여 주는 화면이라 로그인 사용자에게만 띄운다(docs/18)
  { key: "problems", label: "문제", href: "/problems.html", need: "user", tab: "🗂️" },
  // 배지는 "오늘 복습할 게 남았다"를 어느 화면에서든 보이게 하는 장치(loadReviewBadge)
  { key: "review", label: "복습", href: "/review.html", need: "user", badge: "reviewBadge", tab: "🔁" },
  { key: "docs", label: "개념 문서", href: "/documents.html", need: "public", tab: "📚" },
  // 탭 라벨만 "나"로 줄인다 — 탭 다섯 칸에 "내 기록"은 안 들어간다
  { key: "me", label: "내 기록", href: "/me.html", need: "user", tab: "🙂", tabLabel: "나" },
  // 탭 없음: 다섯 상한을 지키느라 뺐다. 입구는 사이드바와 내 기록 화면 안에 있다
  { key: "settings", label: "설정", href: "/settings.html", need: "user", icon: "⚙️" },
  // 관리 콘솔은 "다른 영역으로 나간다"는 뜻이라 화살표를 붙여 다른 메뉴와 구분한다
  { key: "admin", label: "관리 콘솔 ↗", href: "/admin/index.html", need: "admin", icon: "🛠" },
];

/**
 * 화면을 감싸는 껍데기를 그린다 — 넓으면 왼쪽 기둥, 좁으면 위 얇은 바 + 아래 탭바.
 *
 * <p><b>[왜 HTML마다 안 적고 JS가 그리나]</b> 이 프로젝트는 빌드 도구도, head를 공유하는
 * 틀도 없다. 사이드바 마크업을 HTML 열몇 개에 복붙하면 화면이 하나 늘 때마다
 * <b>빠뜨릴 자리가 같이 는다</b>. 실제로 포커스 링 규칙이 한 화면에만 있던 일이 있었다.
 *
 * <p><b>[한 벌로 둘을 그린다]</b> 사이드바와 탭바를 같은 {@link MENUS}에서 만든다.
 * 둘을 따로 적으면 메뉴를 하나 더할 때 한쪽만 고치고 넘어가게 된다.
 *
 * <p><b>[본문을 감싸지 않는다]</b> 셸이 본문을 innerHTML로 감싸면 각 화면이 이미 잡아 둔
 * DOM 참조가 끊긴다. 대신 셸을 본문의 <b>형제</b>로 두고 CSS grid가 자리를 옮긴다 —
 * 마크업 순서와 화면 배치를 떼어놓는 것이 grid를 쓰는 이유다.
 *
 * <p><b>[비로그인도 셸을 본다]</b> 로그인·가입 화면에도 그린다. 예전에는 그 화면들도
 * 상단 바를 띄웠고, 없애면 "여기가 어디지"가 된다. 메뉴는 권한 필터가 알아서 줄인다.
 *
 * @param active MENUS의 key. 지금 화면을 굵게 표시한다. 해당 없으면 빈 문자열
 * @param title  폰 상단 바에 뜨는 화면 이름. 좁은 화면에는 기둥이 없어
 *               "지금 어디인지"를 말해 줄 자리가 여기밖에 없다
 */
function renderShell({ active = "", title = "" } = {}) {
  applyStoredTheme();
  applyStoredFontSize();

  const el = document.getElementById("shell");
  if (!el) return;

  const visible = MENUS.filter(m => hasRole(m.need));
  // 학습 메뉴 / 개인 메뉴 / 콘솔 — 사이드바에서 가로줄로 나뉘는 세 묶음.
  // 묶음을 key로 집어 나누는 이유: 순서만으로 나누면 MENUS에 항목을 끼워 넣을 때
  // 엉뚱한 묶음에 들어가고, 그 사고는 화면을 봐야만 보인다.
  const PERSONAL = ["me", "settings"];   // 내 것을 보는 화면 — 학습 행동과 성격이 다르다
  const study = visible.filter(m => !PERSONAL.includes(m.key) && m.key !== "admin");
  const personal = visible.filter(m => PERSONAL.includes(m.key));
  const console_ = visible.filter(m => m.key === "admin");

  // 탭바는 tab이 있는 것만, 그리고 다섯까지. slice는 안전장치다 —
  // 나중에 tab을 단 항목이 여섯 번째로 늘어도 탭바가 무너지지 않는다.
  const tabs = visible.filter(m => m.tab).slice(0, 5);

  el.innerHTML = `
    <header class="shell-top">
      <a class="brand" href="/">csquiz</a>
      ${title ? `<span class="shell-title">${escapeHtml(title)}</span>` : ""}
      <span class="spacer"></span>
      ${authAreaHtml()}
    </header>

    <nav class="shell-side" aria-label="주 메뉴">
      <a class="brand" href="/">csquiz</a>
      ${sideLinks(study, active)}
      ${personal.length ? `<div class="shell-rule"></div>${sideLinks(personal, active)}` : ""}
      <span class="spacer"></span>
      ${console_.length ? sideLinks(console_, active) : ""}
      <div class="shell-rule"></div>
      <div class="shell-side-auth">${authAreaHtml()}</div>
    </nav>

    <nav class="shell-tabs" aria-label="주 메뉴">
      ${tabs.map(m => `
        <a class="shell-tab${m.key === active ? " active" : ""}" href="${m.href}"
           ${m.key === active ? 'aria-current="page"' : ""}>
          <span class="ic" aria-hidden="true">${m.tab}</span>${escapeHtml(m.tabLabel || m.label)}
          ${m.badge ? `<span id="${m.badge}-tab"></span>` : ""}
        </a>`).join("")}
    </nav>`;

  loadReviewBadge();
  wireLogout();
}

/**
 * 사이드바 링크 한 묶음.
 *
 * <p>아이콘은 {@code tab}(탭바용 그림)을 그대로 재사용하고, 탭에 안 오르는 항목만
 * {@code icon}을 따로 갖는다. 같은 메뉴가 두 자리에서 다른 그림으로 뜨면
 * "이게 그거였나"를 매번 다시 잇게 된다.
 */
function sideLinks(items, active) {
  return items.map(m =>
    `<a class="shell-link${m.key === active ? " active" : ""}" href="${m.href}"
        ${m.key === active ? 'aria-current="page"' : ""}>
       <span class="ic" aria-hidden="true">${m.tab || m.icon || ""}</span>
       <span>${escapeHtml(m.label)}</span>
       ${m.badge ? `<span id="${m.badge}"></span>` : ""}
     </a>`).join("");
}

/**
 * 계정 영역 — 셸의 상단 바와 기둥 아래에 같은 것이 들어간다.
 *
 * <h2>로그아웃이 여기 없다 (2026-09-07)</h2>
 *
 * <p>예전에는 이 마크업에 로그아웃 링크가 들어 있었다. 그런데 이 함수를 셸이 <b>두 번</b>
 * 부른다(상단 바 · 기둥). 링크에 {@code id="logoutLink"}가 박혀 있었으므로 문서에 같은 id가
 * 둘이 됐고, {@code getElementById}는 그중 <b>먼저 나오는 상단 바 것</b>만 돌려준다.
 * 넓은 화면에서 상단 바는 {@code display:none}이라, <b>보이는 쪽 링크에는 아무 동작도
 * 안 붙어 있었다</b> — 눌러도 조용히 아무 일이 없었다. 설정 화면에는 같은 id가 셋이었다.
 *
 * <p>고치는 방법이 둘이었다. id를 자리마다 다르게 주고 배선을 늘리거나, <b>로그아웃을
 * 한 자리에만 두거나</b>. 뒤를 골랐다 — 로그아웃은 드물게 쓰는 동작인데 늘 보이는 자리를
 * 둘이나 차지하고 있었고, 자리가 하나면 "어느 것이 동작하는가"를 물을 일 자체가 없다.
 * 지금 로그아웃은 <b>설정 화면</b>에 있다(관리 콘솔은 설정이 없어 띠에 남긴다).
 *
 * <p>비로그인 상태에서는 <b>회원가입이 주 버튼</b>이다. 처음 온 사람에게 이 자리에서
 * 가장 중요한 것이 그것인데, 예전에는 로그인과 나란한 테두리 버튼이라 무게가 같았다.
 */
function authAreaHtml() {
  const name = escapeHtml(localStorage.getItem(USERNAME_KEY) || "");
  return isLoggedIn()
    // title을 함께 준다 — 기둥이 208px이라 긴 아이디는 잘린다.
    ? `<span class="user-email" title="${name}">${name}</span>`
    : `<a href="/login.html">로그인</a>
       <a href="/signup.html" class="btn">회원가입</a>`;
}

/**
 * 로그아웃에 동작을 붙인다 — <b>{@code data-action="logout"}인 것 전부</b>.
 *
 * <p>id로 하나만 잡던 것을 속성으로 바꿨다. id는 문서에 하나여야 하는데 로그아웃이 놓이는
 * 자리가 여럿이라(설정 화면 · 관리 콘솔 띠) 규칙을 어기고 있었고, 그 대가로 <b>보이는
 * 버튼이 안 눌리는</b> 버그가 있었다({@link authAreaHtml} 주석).
 *
 * <p><b>두 번 걸지 않는다.</b> 설정 화면은 셸이 그려진 뒤 자기도 이 함수를 부른다.
 * 그 버튼은 정적 마크업이라 셸 차례에 이미 걸려 있어서, 표시가 없으면 리스너가 둘이 되고
 * 로그아웃 요청이 두 번 나간다. 걸어 둔 자리에 표를 남겨 두 번째를 건너뛴다.
 */
function wireLogout() {
  document.querySelectorAll('[data-action="logout"]').forEach(el => {
    if (el.dataset.logoutWired) return;
    el.dataset.logoutWired = "1";
    el.addEventListener("click", onLogout);
  });
}

/**
 * 로그아웃 본체.
 *
 * 복사해 두면 안 되는 코드다: 서버의 refresh 토큰 폐기가 빠진 사본이 생기면
 * "로그아웃했는데 서버에는 14일짜리 출입증이 살아 있는" 상태가 그쪽 화면에서만 생긴다.
 */
async function onLogout(e) {
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
    if (data.totalElements > 0) {
      const html = `<span class="nav-badge">${data.totalElements}</span>`;
      // 같은 숫자가 두 자리에 뜬다 — 넓은 화면은 사이드바, 좁은 화면은 탭바.
      // 둘 중 하나만 화면에 보이지만 어느 쪽인지는 CSS가 정하므로 여기서는 둘 다 채운다.
      ["reviewBadge", "reviewBadge-tab"].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.innerHTML = html;
      });
    }
  } catch (e) { /* 배지 실패는 무시(위 주석) */ }
}

/* ═════════════════════════════════════════════════════════════════════
 * 사용자 설정 — 브라우저에만 저장한다 (2026-09-06)
 * ---------------------------------------------------------------------
 * [왜 서버가 아닌가] 이 값들(테마·글자 크기·퀴즈 기본값)은 <기기의 성질>이지 계정의
 * 성질이 아니다. 낮에 회사 모니터에서 라이트, 밤에 폰에서 다크가 자연스럽다.
 * 서버에 두면 기기를 옮길 때마다 도로 바뀐다.
 *
 * [대가] 브라우저 데이터를 지우면 사라진다. 그래서 설정 화면에 "지금 쓰는 브라우저에만
 * 저장됩니다"라고 적는다 — 사라지는 것이 버그로 보이지 않게.
 *
 * [기본값이 한 곳에 있다] 부르는 쪽마다 기본값을 적으면 "이 설정의 기본이 뭔가"의 답이
 * 여러 곳에 생기고, 하나만 고치면 화면마다 다르게 동작한다.
 *
 * [try/catch를 두르는 이유] 시크릿 모드나 사이트 데이터 차단에서는 localStorage에
 * <접근하는 것 자체>가 예외를 던진다. 설정을 못 읽는다고 앱이 멈추면 안 된다.
 * ═══════════════════════════════════════════════════════════════════ */
const PREF_DEFAULTS = {
  csquiz_theme: "auto",       // auto | light | dark
  csquiz_fontsize: "normal",  // small | normal | large
  csquiz_quizsize: "10",      // 자유 퀴즈 한 판의 문제 수
  csquiz_keyhint: "on",       // on | off — 보기 아래 숫자키 안내 줄
};

/** 저장된 값(없으면 기본값). 읽기가 실패해도 기본값으로 계속 간다. */
function getPref(key) {
  try {
    return localStorage.getItem(key) ?? PREF_DEFAULTS[key];
  } catch (e) {
    return PREF_DEFAULTS[key];
  }
}

/** 저장. 실패해도 조용히 넘어간다 — 이번 화면에는 이미 적용돼 있다. */
function setPref(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* 저장 못 해도 진행 */ }
}

/**
 * 저장된 테마를 <html>에 붙인다.
 *
 * <p>"auto"는 <b>속성을 지우는 것</b>으로 표현한다. 값을 넣지 않으면 기기 설정을 보는
 * 미디어 쿼리가 살아나기 때문이다 — 즉 자동은 "아무것도 강제하지 않음"이다.
 *
 * <p>첫 그림이 번쩍이는 것(FOUC)을 막는 진짜 장치는 각 화면 {@code <head>}의 인라인
 * 한 줄이다(개편 마지막 단계). 이 함수는 그 뒤에 한 번 더 부르는 안전망이라,
 * head 한 줄을 빠뜨린 화면이 생겨도 늦게나마 맞는다.
 */
function applyStoredTheme() {
  try {
    const t = getPref("csquiz_theme");
    if (t === "dark" || t === "light") document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  } catch (e) { /* 기본(자동)으로 둔다 */ }
}

/**
 * 글자 크기 — <html>에 클래스를 붙이고 CSS가 <b>문제 지문만</b> 키운다.
 *
 * <p>본문 전체를 키우지 않는 이유: 메뉴·목록까지 커지면 한 화면에 들어가는 줄이 줄어
 * 오히려 읽기 나빠진다. 키워서 득을 보는 것은 오래 들여다보는 문제 지문이다.
 */
function applyStoredFontSize() {
  const v = getPref("csquiz_fontsize");
  document.documentElement.classList.remove("fs-small", "fs-large");
  if (v === "small") document.documentElement.classList.add("fs-small");
  if (v === "large") document.documentElement.classList.add("fs-large");
}
