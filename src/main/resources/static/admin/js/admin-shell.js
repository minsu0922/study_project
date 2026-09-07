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
 * 콘솔 메뉴 — <b>세 묶음</b>으로 나눈다.
 *
 * <p>예전에는 평평한 탭 일곱이었다. "지금 할 일"(검수·제보)과 "가끔 보는 것"(문제 목록,
 * 배치 현황)이 같은 줄에 서 있어서, 콘솔을 여는 이유의 대부분인 검수가 다른 것들과
 * 같은 무게로 보였다.
 *
 * <p><b>배지의 뜻이 항목마다 다르다.</b> 셋 다 숫자 배지지만 읽는 법이 같지 않다 —
 * 자세한 것은 {@link refreshAdminBadges}. 여기서는 "어느 자리에 붙는가"만 정한다.
 *
 * <h2>현황(index)이 메뉴에서 빠졌다</h2>
 *
 * <p>콘솔에 들어오는 이유가 대부분 검수라 <b>검수를 첫 화면</b>으로 삼는다. 현황 화면을
 * 지우지는 않았다 — 출제 분포 히트맵과 승인율 차트는 거기서만 볼 수 있다. 기둥 맨 위
 * "현황"을 누르면 간다.
 *
 * <h2>"주제 범위"도 항목이 아니다</h2>
 *
 * <p>그 화면은 2026-08-29에 <b>생성 화면 안으로 흡수됐다</b>(topics.html은 넘겨주기만 한다).
 * 주제를 정하는 것은 생성의 <입력>이지 별개 작업이 아니라는 판단이었고, 여전히 맞다.
 * 그래서 주제 범위 상태는 "생성 실행" 항목의 배지로 보인다.
 */
const ADMIN_MENUS = [
  { group: "할 일", items: [
    // 제보가 검수 옆인 것은 하는 일이 같아서다 — 둘 다 <읽고 판정하는> 화면이고,
    // 다른 것은 판정 대상이 출제 전이냐 후냐뿐이다. 검수함이 비면 제보함을 보는 흐름.
    { key: "llm",       label: "검수",      href: "/admin/llm.html",       icon: "📋" },
    { key: "reports",   label: "제보",      href: "/admin/reports.html",   icon: "🚩" },
  ]},
  { group: "콘텐츠", items: [
    { key: "problems",  label: "문제",      href: "/admin/problems.html",  icon: "🗂️" },
    { key: "documents", label: "문서",      href: "/admin/documents.html", icon: "📚" },
  ]},
  { group: "생성", items: [
    { key: "generate",  label: "생성 실행", href: "/admin/generate.html",  icon: "✨" },
    // 배치가 맨 끝인 것은 자리가 남아서가 아니다. 메뉴가 일의 단계 순인데 배치는
    // 단계가 아니라 <기계 상태>라 그 흐름에 낄 자리가 없다. 매일 보는 것도 아니다.
    { key: "batch",     label: "배치 현황", href: "/admin/batch.html",     icon: "📅" },
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
 * @param active ADMIN_MENUS의 key. 해당 없으면 빈 문자열(현황 화면이 그렇다)
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
      <a href="#" id="logoutLink">로그아웃</a>
    </header>

    <nav class="shell-side" aria-label="관리 메뉴">
      <a class="brand" href="/admin/index.html">현황</a>
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
 * <p>넷을 한꺼번에 보낸다 — 순서대로 기다리면 화면이 뜨는 데 왕복 네 번이 그대로 더해진다.
 *
 * <h2>같은 모양인데 읽는 법이 다르다</h2>
 *
 * <ul>
 *   <li><b>검수</b> — 문제·문서 대기를 합친 수. <b>회색</b>이다. 매일 쌓이는 <일상>이라
 *       하나 있다고 급한 것이 아니다. 둘을 합치는 것은 <b>한 화면이 둘 다 다루기</b>
 *       때문이다 — 배지가 하나여야 "저기 들어가면 할 일이 있다"가 정확해진다.
 *   <li><b>제보</b> — <b>주황</b>이다. "출제된 문제가 틀렸을지 모른다"는 신호라 하나만
 *       있어도 눈에 걸려야 한다. 검수 배지에 합치지 않은 이유이기도 하다.
 *   <li><b>생성</b> — <b>뜻이 반대다.</b> 주제 범위가 0이면 배치가 모델 자동 선택으로
 *       돌아가므로, <b>0일 때</b> "범위 없음"을 주황으로 띄운다. 숫자를 세는 배지가 아니다.
 * </ul>
 */
async function refreshAdminBadges() {
  const [problems, documents, topics, reports] = await Promise.all([
    countOf("/api/admin/llm-problems/pending-count"),
    countOf("/api/admin/llm-documents/pending-count"),
    countOf("/api/admin/topic-queue/count"),
    countOf("/api/admin/reports/pending-count"),
  ]);

  setBadge("llm", problems + documents, false);
  setBadge("generate", topics === 0 ? "범위 없음" : null, topics === 0);
  setBadge("reports", reports, reports > 0);

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
  document.dispatchEvent(new CustomEvent("admin:counts", {
    detail: { problems, documents, topics, reports },
  }));
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
