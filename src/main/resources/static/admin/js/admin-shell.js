/* =====================================================================
 * admin-shell.js — 관리 콘솔의 껍데기 (2026-09-06 콘솔 개편)
 * ---------------------------------------------------------------------
 * [왜 사용자 셸(shell.js)과 나누나]
 * 콘솔은 <다른 방>이다. 같은 기둥에 학습 메뉴와 관리 메뉴가 함께 뜨면 "지금 어느 쪽에
 * 있는지"가 흐려지고, 검수하다 잘못 눌러 나가기 쉽다. 이 판단은 개편 전부터 이 코드의
 * 조상(admin-common.js의 renderConsoleNav)에 적혀 있었고, 시안에서 기둥을 공유하는 안이
 * 더 빨랐지만 뒤집을 새 근거가 없어 그대로 뒀다.
 *
 * [그래도 shell.js를 부른다] authAreaHtml·wireLogout·테마 적용은 그쪽 것을 쓴다.
 * 복사해 두면 서버 토큰 폐기가 빠진 사본이 생겨, 콘솔에서 로그아웃했을 때만 서버에
 * 14일짜리 출입증이 남는 상태가 된다.
 *
 * [로드 순서] api.js → shell.js → admin-shell.js → admin-common.js.
 * 번들러가 없어 순서가 곧 의존성이다. AdminPageStructureTest가 그 순서를 지킨다.
 * ===================================================================== */

/**
 * 콘솔 메뉴 — 일의 종류로 묶는다.
 *
 * <p>예전에는 평평한 탭 일곱이었다. "지금 할 일"(검수·제보)과 "가끔 보는 것"(문제 목록,
 * 배치 현황)이 같은 줄에 서 있어서, 콘솔을 여는 이유의 대부분인 검수가 다른 것들과
 * 같은 무게로 보였다. 그래서 할 일을 맨 위에 두고, 콘솔 입구도 검수 화면이다.
 *
 * <p><b>배지의 뜻이 항목마다 다르다.</b> 다 숫자 배지지만 읽는 법이 같지 않다 —
 * 자세한 것은 {@link refreshAdminBadges}. 여기서는 "어느 자리에 붙는가"만 정한다.
 *
 * <h2>한 메뉴에 한 기능 (2026-10-15)</h2>
 *
 * <p>화면 하나에 기능이 둘 이상 붙어 있던 곳을 나눴다. 생성 화면에 있던 주제 대기열,
 * 문제 화면에 있던 AI 일괄 작업, 현황 화면에 섞여 있던 학습 통계·출제 분포·승인율이
 * 각자 메뉴가 됐다. 승인율은 배치 현황에도 있어 AI 품질 하나로 합쳤다.
 * 현황은 메뉴 밖 기둥 맨 위 글자로만 들어가던 화면이라, 나누면서 메뉴로 올렸다.
 */
const ADMIN_MENUS = [
  { group: "할 일", items: [
    // 제보가 검수 옆인 것은 하는 일이 같아서다 — 둘 다 <읽고 판정하는> 화면이고,
    // 다른 것은 판정 대상이 출제 전이냐 후냐뿐이다. 검수함이 비면 제보함을 보는 흐름.
    { key: "llm",       label: "검수",        href: "/admin/llm.html",       icon: "📋" },
    { key: "reports",   label: "오류 제보",   href: "/admin/reports.html",   icon: "🚩" },
    // 제보 옆인 이유: 둘 다 학습자가 올린 것을 읽고 판정하는 화면이다. 대상이 문제냐 토론방의 글·댓글이냐만 다르다.
    { key: "comments",  label: "게시글 신고", href: "/admin/comments.html",  icon: "💬" },
  ]},
  { group: "콘텐츠", items: [
    { key: "problems",  label: "문제",      href: "/admin/problems.html",  icon: "🗂️" },
    { key: "documents", label: "문서",      href: "/admin/documents.html", icon: "📚" },
  ]},
  // 이 묶음의 화면은 전부 Claude를 부른다(주제 대기열은 배치가 부를 것을 정한다).
  { group: "AI 생성", items: [
    { key: "generate",  label: "직접 생성",   href: "/admin/generate.html",  icon: "✨" },
    { key: "topics",    label: "주제 대기열", href: "/admin/topics.html",    icon: "🧭" },
    { key: "backfill",  label: "일괄 보정",   href: "/admin/backfill.html",  icon: "🩹" },
  ]},
  // 배치는 일의 단계가 아니라 <기계>다. 상태(현황)와 설정(분야)은 한 기계의 두 면이라 나란히 둔다.
  { group: "배치", items: [
    { key: "batch",     label: "배치 현황", href: "/admin/batch.html",     icon: "📅" },
    { key: "settings",  label: "분야 설정", href: "/admin/settings.html",  icon: "⚙️" },
  ]},
  // 읽기만 하는 화면들. 매일 볼 것은 아니고 무엇을 고칠지 정할 때 본다.
  { group: "통계", items: [
    { key: "stats",        label: "학습 현황", href: "/admin/index.html",        icon: "📈" },
    { key: "distribution", label: "출제 분포", href: "/admin/distribution.html", icon: "🧮" },
    { key: "quality",      label: "AI 품질",   href: "/admin/quality.html",      icon: "🎯" },
  ]},
  // 따로 묶은 이유: 위 셋은 문제와 문서를 다루고 여기는 사람을 다룬다. 매일 보는 화면도 아니다 —
  // 대개 토론 신고의 "글쓴이 정지"로 넘어온다.
  { group: "운영", items: [
    { key: "users",     label: "사용자",    href: "/admin/users.html",     icon: "👤" },
    // 관리자가 한 쓰기 요청의 기록. 사람을 다루는 일의 뒤를 확인하러 오는 자리라 사용자 아래에 둔다.
    { key: "audit",     label: "처리 기록", href: "/admin/audit.html",     icon: "🧾" },
  ]},
];

/**
 * 콘솔 셸을 그린다 — 상단 진한 띠 + 좌측 기둥.
 *
 * <p><b>[왜 진한 띠인가]</b> 콘솔이 다른 방이라는 것을 <b>색으로</b> 알린다. 기둥 내용만으로도
 * 구별되지만, 검수는 화면을 아래로 길게 훑는 작업이라 기둥이 시야 밖으로 나간다.
 * 띠는 sticky라 늘 보인다.
 *
 * <p><b>[나가는 문은 하나]</b> "← 학습 화면으로"를 띠에 둔다. 관리자도 학습자라 오갈 일이
 * 있고 막을 이유가 없다 — 다만 <b>메뉴가 아니라 문</b>이라 기둥이 아니라 띠에 있다.
 *
 * @param active ADMIN_MENUS의 key. 사용자 상세처럼 메뉴에 없는 화면은 그 부모의 key를 준다
 */
function renderAdminShell(active = "") {
  applyStoredTheme();
  applyStoredFontSize();

  const el = document.getElementById("shell");
  if (!el) return;

  el.innerHTML = `
    <header class="admin-bar">
      <a class="brand" href="/admin/llm.html">csquiz 관리</a>
      <span class="who">${escapeHtml(localStorage.getItem(USERNAME_KEY) || "")}</span>
      <span class="spacer"></span>
      <a href="/">← 학습 화면으로</a>
      <!-- 콘솔에는 설정 화면이 없어 로그아웃이 띠에 남는다. 사용자 화면 쪽은
           설정 안으로 옮겼다(shell.js authAreaHtml 주석). -->
      <a href="#" data-action="logout">로그아웃</a>
    </header>

    <nav class="shell-side" aria-label="관리 메뉴">
      ${ADMIN_MENUS.map(g => `
        <div class="shell-group">${escapeHtml(g.group)}</div>
        ${g.items.map(m => `
          <a class="shell-link${m.key === active ? " active" : ""}" href="${m.href}"
             ${m.key === active ? 'aria-current="page"' : ""}>
            <span class="ic" aria-hidden="true">${m.icon}</span>
            <span>${escapeHtml(m.label)}</span>
            <span id="badge-${m.key}" class="badge gray" hidden></span>
          </a>`).join("")}`).join("")}
    </nav>`;

  wireLogout();
}

/**
 * 기둥의 배지를 갱신한다 — <b>셋의 뜻이 서로 다르다</b>.
 *
 * <p>배지를 <b>모든 화면</b>에서 부르는 것은 의도다. "검수할 게 남았다"는 지금 보고 있는
 * 화면과 무관하게 알아야 하는 정보이고, 화면을 옮길 때마다 최신값이 보인다.
 *
 * <p>개수 전부를 요청 하나로 받는다(2026-10-15). 개수마다 따로 부르던 때는 화면을 열 때마다
 * 여덟 번씩 나가, 콘솔에서 화면을 몇 번 옮기면 요청 한도(분당 60회)에 걸렸다.
 *
 * <h2>같은 모양인데 읽는 법이 다르다</h2>
 *
 * <ul>
 *   <li><b>검수</b> — 문제·문서 대기를 합친 수. <b>회색</b>이다. 매일 쌓이는 <일상>이라
 *       하나 있다고 급한 것이 아니다. 둘을 합치는 것은 <b>한 화면이 둘 다 다루기</b>
 *       때문이다 — 배지가 하나여야 "저기 들어가면 할 일이 있다"가 정확해진다.
 *   <li><b>제보</b> — <b>주황</b>이다. "출제된 문제가 틀렸을지 모른다"는 신호라 하나만
 *       있어도 눈에 걸려야 한다. 검수 배지에 합치지 않은 이유이기도 하다.
 *       문제 제보 대기 수와 토론방 "오류 지적" 글 중 답 전인 수를 합친다 — 둘 다 그 화면에서 본다.
 *   <li><b>주제 대기열</b> — <b>뜻이 반대다.</b> 개념 주제가 0이면 배치가 모델 자동 선택으로
 *       돌아가므로, <b>0일 때</b> "범위 없음"을 주황으로 띄운다. 숫자를 세는 배지가 아니다.
 *   <li><b>일괄 보정</b> — 제목이나 오답 설명이 빈 문제 수. 회색이다. 급한 일이 아니라 남은 일이다.
 * </ul>
 */
async function refreshAdminBadges() {
  let c;
  try {
    c = await api("/api/admin/badge-counts");
  } catch (e) {
    // 배지는 장식이라 화면을 막지 않는다. 0으로 채우지도 않는다 — 주제 대기열은 0이 경고라 거짓 경보가 된다
    return;
  }
  const { problems, documents, topics, reports, commentReports, errata } = c;

  setBadge("llm", problems + documents, false);
  setBadge("topics", topics === 0 ? "범위 없음" : null, topics === 0);
  setBadge("backfill", c.untitled + c.missingRationale, false);
  setBadge("reports", reports + errata, reports + errata > 0);
  setBadge("comments", commentReports, commentReports > 0);

  /* 센 숫자를 화면 쪽에도 흘린다.
   *
   * 검수 화면 위의 요약 넷이 이 값과 <같은 숫자>다. 화면이 직접 세게 두면 같은 주소를
   * 네 번 더 부르게 되고, 그보다 나쁜 것은 두 숫자가 어긋나는 순간이 생긴다는 점이다 —
   * 승인 직후 배지는 줄었는데 요약은 그대로면, 보는 사람은 어느 쪽을 믿어야 할지 모른다.
   *
   * 함수를 부르지 않고 알림(이벤트)으로 흘리는 이유는 <부르는 자리가 일곱>이라서다.
   * 검수 화면은 승인·거절·일괄승인 등 일곱 곳에서 이 함수를 부르는데, 요약 갱신을
   * 그 일곱 곳에 손으로 붙이면 여덟 번째를 더하는 날 하나를 빠뜨린다.
   * 여기서 한 번 알리면 듣는 쪽이 알아서 따라온다. */
  document.dispatchEvent(new CustomEvent("admin:counts", { detail: c }));
}

/** 배지 하나 — 값이 null이거나 0이면 숨긴다(0을 붙여 두면 늘 시끄럽다). */
function setBadge(key, value, highlight) {
  const el = document.getElementById("badge-" + key);
  if (!el) return;
  const hide = value === null || value === 0;
  el.textContent = value;
  el.className = highlight ? "badge orange" : "badge gray";
  el.hidden = hide;
}
