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
    { key: "llm",       label: "검수",        href: "/admin/llm.html",       icon: "clipboard" },
    { key: "reports",   label: "오류 제보",   href: "/admin/reports.html",   icon: "flag" },
    // 제보 옆인 이유: 둘 다 학습자가 올린 것을 읽고 판정하는 화면이다. 대상이 문제냐 토론방의 글·댓글이냐만 다르다.
    { key: "comments",  label: "게시글 신고", href: "/admin/comments.html",  icon: "message" },
  ]},
  { group: "콘텐츠", items: [
    { key: "problems",  label: "문제",      href: "/admin/problems.html",  icon: "list" },
    { key: "documents", label: "문서",      href: "/admin/documents.html", icon: "book" },
    // 문서를 순서대로 엮은 추천 코스(docs/26). 분야별·코드 코스는 규칙으로 만들어 여기 없다
    { key: "paths",     label: "로드맵",    href: "/admin/paths.html",     icon: "route" },
  ]},
  // 이 묶음의 화면은 전부 Claude를 부른다(주제 대기열은 배치가 부를 것을 정한다).
  { group: "AI 생성", items: [
    { key: "generate",  label: "직접 생성",   href: "/admin/generate.html",  icon: "sparkles" },
    { key: "topics",    label: "주제 대기열", href: "/admin/topics.html",    icon: "queue" },
    { key: "backfill",  label: "일괄 보정",   href: "/admin/backfill.html",  icon: "wand" },
  ]},
  // 배치는 일의 단계가 아니라 <기계>다. 상태(현황)와 설정(분야)은 한 기계의 두 면이라 나란히 둔다.
  { group: "배치", items: [
    { key: "batch",     label: "배치 현황", href: "/admin/batch.html",     icon: "calendar" },
    { key: "settings",  label: "분야 설정", href: "/admin/settings.html",  icon: "sliders" },
  ]},
  // 읽기만 하는 화면들. 매일 볼 것은 아니고 무엇을 고칠지 정할 때 본다.
  { group: "통계", items: [
    { key: "stats",        label: "학습 현황", href: "/admin/index.html",        icon: "chart" },
    { key: "distribution", label: "출제 분포", href: "/admin/distribution.html", icon: "pie" },
    { key: "quality",      label: "AI 품질",   href: "/admin/quality.html",      icon: "target" },
  ]},
  // 따로 묶은 이유: 위 셋은 문제와 문서를 다루고 여기는 사람을 다룬다. 매일 보는 화면도 아니다 —
  // 대개 토론 신고의 "글쓴이 정지"로 넘어온다.
  { group: "운영", items: [
    { key: "users",     label: "사용자",    href: "/admin/users.html",     icon: "user" },
    // 관리자가 한 쓰기 요청의 기록. 사람을 다루는 일의 뒤를 확인하러 오는 자리라 사용자 아래에 둔다.
    { key: "audit",     label: "처리 기록", href: "/admin/audit.html",     icon: "fileText" },
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
      <a class="brand" href="/admin/llm.html"><span class="brand-site">csquiz&nbsp;</span>관리</a>
      <!-- 배치 칩 — 어느 화면에서든 알아야 하는 기계 상태. 배지가 세는 응답에서 채운다 -->
      <a class="bar-chip" id="barBatch" href="/admin/batch.html" hidden></a>
      <span class="spacer"></span>
      <!-- 통합 검색. 검수·제보·대화에서 문제를 번호나 제목으로 부르는데 목록을 넘겨 찾아야 했다 -->
      <form class="bar-jump" id="barJump" role="search">
        <input id="barJumpInput" type="search" autocomplete="off" maxlength="50"
               placeholder="검색  /" aria-label="번호, 제목, 사용자로 검색 (단축키 /)"
               role="combobox" aria-expanded="false" aria-controls="barJumpPop">
        <div class="bar-jump-pop" id="barJumpPop" role="listbox" hidden></div>
      </form>
      <!-- 좁은 화면에는 검색 칸이 들어갈 자리가 없어 돋보기만 둔다. 누르면 띠 아래로 칸이 펼쳐진다 -->
      <button type="button" class="bar-icon bar-search-btn" id="barSearchBtn"
              aria-label="검색 열기" aria-controls="barJump" aria-expanded="false">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
             stroke-linecap="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>
      </button>
      <!-- 콘솔에는 설정 화면이 없어 테마를 바꾸려면 학습 화면 설정까지 다녀와야 했다 -->
      <button type="button" class="bar-icon" id="barTheme"></button>
      <span class="who">${escapeHtml(localStorage.getItem(USERNAME_KEY) || "")} · 관리자</span>
      <!-- &nbsp;인 이유: 링크가 flex라 칸 앞의 보통 공백은 지워진다 -->
      <a href="/">← 학습<span class="bar-wide">&nbsp;화면으로</span></a>
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
            <span class="ic">${icon(m.icon)}</span>
            <span>${escapeHtml(m.label)}</span>
            <span id="badge-${m.key}" class="badge gray" hidden></span>
          </a>`).join("")}`).join("")}
    </nav>`;

  wireLogout();
  wireThemeButton();
  wireJump();
}

/* ── 테마 전환 ── 자동 → 라이트 → 다크 → 자동. 값은 학습 화면 설정과 같은 칸에 저장한다 */
const THEME_CYCLE = { auto: "light", light: "dark", dark: "auto" };
const THEME_LABEL = { auto: ["contrast", "테마: 기기 설정 따름"], light: ["sun", "테마: 라이트"], dark: ["moon", "테마: 다크"] };

function wireThemeButton() {
  const btn = document.getElementById("barTheme");
  const paint = () => {
    const t = THEME_LABEL[getPref("csquiz_theme")] ? getPref("csquiz_theme") : "auto";
    btn.innerHTML = icon(THEME_LABEL[t][0]);
    // 누르면 무엇이 되는지까지 알려 준다. 지금 상태만 말하면 세 번 눌러 봐야 순서를 안다
    btn.title = `${THEME_LABEL[t][1]} (누르면 ${THEME_LABEL[THEME_CYCLE[t]][1].replace("테마: ", "")})`;
    btn.setAttribute("aria-label", btn.title);
  };
  paint();
  btn.addEventListener("click", () => {
    const now = THEME_LABEL[getPref("csquiz_theme")] ? getPref("csquiz_theme") : "auto";
    setPref("csquiz_theme", THEME_CYCLE[now]);
    applyStoredTheme();
    paint();
  });
}

/* ── 통합 검색 ──
 * 숫자는 번호로(문제와 초안은 번호 체계가 따로라 둘 다 나올 수 있다), 글자는 문제·초안·문서의 제목과
 * 지문, 사용자 아이디·닉네임으로 찾는다. 치는 동안 결과가 뜨고 ↑↓·Enter로 고른다.
 * 쉬는 시간(SEARCH_DELAY_MS)은 목록 화면의 검색 칸과 같은 값이다(admin-common.js).
 *
 * 빈 칸을 누르면 안내를 띄운다. 무엇을 칠 수 있는지와, 이 브라우저에서 자주 찾은 검색어다.
 * 검색어는 서버에 남기지 않는다. 관리자가 한 명이라 서버에서 세도 이 브라우저의 기록과 같다. */
const DRAFT_STATUS_LABEL = { PENDING: "검수 대기", APPROVED: "승인됨", REJECTED: "거절됨" };
const SEARCH_HISTORY_KEY = "csquiz_admin_search_history";
const SEARCH_HISTORY_SHOWN = 6;
/** 기록이 끝없이 늘지 않게 이만큼만 둔다. 넘치면 가장 오래 안 쓴 것부터 버린다 */
const SEARCH_HISTORY_KEPT = 30;
/** 범위 칩. 고르면 그 종류만 보여 준다. 예시는 칩을 눌렀을 때 칸에 미리 넣어 줄 글이다 */
const SEARCH_SCOPES = [
  ["number", "번호", "#"],
  ["problems", "문제", ""],
  ["drafts", "문제 초안", ""],
  ["documents", "문서", ""],
  ["users", "사용자", ""],
];

function readSearchHistory() {
  try {
    const list = JSON.parse(localStorage.getItem(SEARCH_HISTORY_KEY) || "[]");
    return Array.isArray(list) ? list.filter(h => h && typeof h.q === "string") : [];
  } catch (e) {
    return [];
  }
}

/** 결과로 실제 이동한 검색어만 센다. 치다 만 글자까지 세면 "TC" 같은 조각이 위로 올라온다 */
function rememberSearch(q) {
  const list = readSearchHistory();
  const hit = list.find(h => h.q === q);
  if (hit) {
    hit.count++;
    hit.at = Date.now();
  } else {
    list.push({ q, count: 1, at: Date.now() });
  }
  list.sort((a, b) => b.at - a.at);
  try {
    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(list.slice(0, SEARCH_HISTORY_KEPT)));
  } catch (e) { /* 저장 못 해도 이동은 한다 */ }
}

function forgetSearch(q) {
  try {
    localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(readSearchHistory().filter(h => h.q !== q)));
  } catch (e) { /* 다음에 다시 보여도 해가 없다 */ }
}

function wireJump() {
  const form = document.getElementById("barJump");
  const input = document.getElementById("barJumpInput");
  const pop = document.getElementById("barJumpPop");
  const bar = form.closest(".admin-bar");
  const openBtn = document.getElementById("barSearchBtn");
  let timer = null;
  let seq = 0;
  let links = [];
  let cursor = -1;
  let scope = null;

  const close = () => {
    pop.hidden = true;
    input.setAttribute("aria-expanded", "false");
    links = [];
    cursor = -1;
  };
  // 좁은 화면에서 돋보기로 펼친 칸을 접는다. 넓은 화면에서는 클래스만 빠지고 달라지는 것이 없다
  const fold = () => {
    bar.classList.remove("searching");
    openBtn.setAttribute("aria-expanded", "false");
  };
  const unfold = () => {
    bar.classList.add("searching");
    openBtn.setAttribute("aria-expanded", "true");
    input.focus();
  };
  const setScope = s => {
    scope = s;
    const label = SEARCH_SCOPES.find(([key]) => key === s)?.[1];
    input.placeholder = label ? `${label}에서 검색` : "검색  /";
  };
  const showGuide = () => {
    show(renderSearchGuide(scope));
    pop.dataset.q = "";
    // 안내에서는 아무것도 골라 두지 않는다. 빈 칸에서 Enter를 눌렀다고 칩이 눌리면 안 된다
    cursor = -1;
    mark();
  };
  const show = html => {
    pop.innerHTML = html;
    pop.hidden = false;
    input.setAttribute("aria-expanded", "true");
    links = [...pop.querySelectorAll("a:not(.x)")]; // 기록 지우기(✕)는 ↑↓로 지나가지 않는다
    cursor = links.length ? 0 : -1;
    mark();
  };
  const mark = () => links.forEach((a, i) => a.classList.toggle("on", i === cursor));

  async function search() {
    const q = input.value.trim();
    // "#"만 있으면 번호 칩을 막 누른 참이다. 번호를 칠 때까지 안내를 그대로 둔다
    if (!q || q === "#") return showGuide();
    // 한 글자 글자 검색은 거의 모든 줄에 걸려 쓸모가 없다. 숫자는 한 자리도 번호다
    if (!/^#?\d+$/.test(q) && q.length < 2) return close();
    const mine = ++seq;
    let r;
    try {
      r = await api(`/api/admin/lookup?q=${encodeURIComponent(q)}`);
    } catch (err) {
      if (mine === seq) show(`<p class="bar-jump-empty">찾지 못했습니다: ${escapeHtml(err.message)}</p>`);
      return;
    }
    if (mine !== seq) return; // 늦게 온 앞 검색의 답은 버린다
    show(renderResults(q, r, scope));
    pop.dataset.q = q;
  }

  input.addEventListener("input", () => {
    clearTimeout(timer);
    // 칸을 비우면 기다리지 않고 바로 안내로 돌아간다
    if (!input.value.trim()) return showGuide();
    timer = setTimeout(search, SEARCH_DELAY_MS);
  });
  input.addEventListener("focus", search);
  openBtn.addEventListener("click", () => (bar.classList.contains("searching") ? fold() : unfold()));

  // 결과·안내 상자 안의 누름. 안내 항목은 이동이 아니라 칸을 채우는 일이라 여기서 가로챈다
  pop.addEventListener("click", e => {
    const a = e.target.closest("a");
    if (!a) return;
    if (a.dataset.forget !== undefined) {
      e.preventDefault();
      forgetSearch(a.dataset.forget);
      showGuide();
      input.focus();
      return;
    }
    if (a.dataset.scope !== undefined) {
      e.preventDefault();
      setScope(a.dataset.scope === scope ? null : a.dataset.scope);
      if (a.dataset.fill) input.value = a.dataset.fill;
      input.focus();
      search();
      return;
    }
    if (a.dataset.fillQuery !== undefined) {
      e.preventDefault();
      input.value = a.dataset.fillQuery;
      input.focus();
      search();
      return;
    }
    if (pop.dataset.q) rememberSearch(pop.dataset.q);
  });
  input.addEventListener("keydown", e => {
    if (pop.hidden || !links.length) return;
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const step = e.key === "ArrowDown" ? 1 : -1;
      // 안내처럼 아무것도 안 골랐으면(-1) ↓는 첫 줄, ↑는 끝 줄로 간다
      cursor = cursor < 0 ? (step > 0 ? 0 : links.length - 1) : (cursor + step + links.length) % links.length;
      mark();
      links[cursor].scrollIntoView({ block: "nearest" });
    }
  });
  form.addEventListener("submit", async e => {
    e.preventDefault();
    clearTimeout(timer);
    // 쉬는 300ms 안에 Enter를 누르면 떠 있는 것은 앞 글자의 결과다. 새로 찾고 첫 줄로 간다
    if (pop.hidden || pop.dataset.q !== input.value.trim()) await search();
    // 안내 항목(칩·검색어)은 이동이 아니라 칸 채우기라, 주소로 가지 않고 누른 것처럼 처리한다
    if (links[cursor]) links[cursor].click();
  });
  document.addEventListener("keydown", e => {
    if (e.key === "Escape") { close(); fold(); input.blur(); }
    // "/" 단축키. 글을 쓰는 칸에서는 그 글자 그대로 둔다. 좁은 화면이면 칸을 펼친다
    const t = e.target;
    if (e.key === "/" && !e.ctrlKey && !e.metaKey && !e.altKey
        && !(t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement || t.isContentEditable)) {
      e.preventDefault();
      unfold();
      input.select();
    }
  });
  document.addEventListener("click", e => {
    if (form.contains(e.target) || openBtn.contains(e.target)) return;
    close();
    fold();
  });
}

/**
 * 빈 칸의 안내. 무엇으로 찾을 수 있는지(범위 칩)와 자주 찾은 검색어다.
 * 칩은 범위를 고르는 단추이기도 하다. 고르면 결과에 그 종류만 남는다.
 */
function renderSearchGuide(scope) {
  const chips = SEARCH_SCOPES.map(([key, label, fill]) => `
    <a href="#" role="option" class="chip${key === scope ? " on" : ""}" data-scope="${key}" data-fill="${fill}">${label}</a>`).join("");
  // 자주 쓴 것이 위로, 횟수가 같으면 최근 것이 위로
  const history = readSearchHistory()
    .sort((a, b) => b.count - a.count || b.at - a.at)
    .slice(0, SEARCH_HISTORY_SHOWN);
  const rows = history.length
    ? history.map(h => `
        <div class="bar-jump-history">
          <a href="#" role="option" data-fill-query="${escapeHtml(h.q)}">
            <span class="t">${escapeHtml(h.q)}</span>
            <span class="tag">${h.count}번</span>
          </a>
          <a href="#" class="x" data-forget="${escapeHtml(h.q)}" aria-label="${escapeHtml(h.q)} 기록 지우기">✕</a>
        </div>`).join("")
    : `<p class="bar-jump-empty">아직 없습니다. 결과로 이동한 검색어가 여기에 쌓입니다.</p>`;
  return `
    <div class="bar-jump-group">찾을 수 있는 것</div>
    <div class="bar-jump-chips">${chips}</div>
    <p class="bar-jump-hint">#7339처럼 번호, 제목·지문 일부, 아이디나 닉네임을 칩니다.</p>
    <div class="bar-jump-group">자주 찾은 검색어</div>
    ${rows}`;
}

/**
 * 결과 상자. 종류마다 [이름, 결과, 한 줄로 바꾸기, 모두 보기 주소]다.
 * 모두 보기는 결과가 상자에 다 안 들어갔을 때만 단다. 그 목록 화면이 같은 검색어로 같은 결과를 낸다.
 * 초안은 모두 보기가 없다. 검수함은 상태를 하나씩 보는데 띠는 검수 대기·거절됨을 함께 찾는다.
 */
function renderResults(q, r, scope) {
  const enc = encodeURIComponent(q);
  // 번호 칩은 거르지 않는다. 번호 검색은 원래 문제와 초안만 돌려준다
  const keep = { problems: "문제", drafts: "문제 초안", documents: "문서", users: "사용자" }[scope];
  // 문제가 먼저다. 사람들이 가장 자주 찾는 것이고, 고칠 곳도 초안이 아니라 문제다
  const kinds = [
    ["문제", r.problems, p => [`/admin/problems.html?edit=${p.id}`, `#${p.id}`, p.title, ""],
      `/admin/problems.html?q=${enc}`],
    ["문제 초안", r.drafts, d => [`/admin/llm.html?draft=${d.id}&kind=${d.code ? "code" : "concept"}&status=${d.status}`,
      `#${d.id}`, d.title, `${d.code ? "코드 · " : ""}${DRAFT_STATUS_LABEL[d.status] || d.status}`], null],
    ["문서", r.documents, d => [`/admin/documents.html?edit=${d.id}`, `#${d.id}`, d.title, d.hidden ? "내림" : ""],
      `/admin/documents.html?q=${enc}`],
    ["사용자", r.users, u => [`/admin/user.html?id=${u.id}`, u.username, u.nickname, ""],
      `/admin/users.html?q=${enc}`],
  ].filter(([name, section]) => section.items.length && (!keep || name === keep));
  // 범위를 골랐으면 결과 위에 적어 둔다. 칸의 안내 글은 글자를 치는 순간 사라진다
  const scopeLine = keep
    ? `<p class="bar-jump-hint">${keep}만 보는 중 · <a href="#" class="inline" data-scope="${scope}">전체 보기</a></p>` : "";
  if (!kinds.length) return scopeLine + `<p class="bar-jump-empty">"${escapeHtml(q)}"에 맞는 것이 없습니다.</p>`;
  return scopeLine + kinds.map(([name, section, toRow, moreHref]) => `
    <div class="bar-jump-group">${name}</div>
    ${section.items.map(toRow).map(([href, key, text, tag]) => `
      <a href="${href}" role="option">
        <span class="k">${escapeHtml(key)}</span>
        <span class="t">${escapeHtml(text || "(제목 없음)")}</span>
        ${tag ? `<span class="tag">${escapeHtml(tag)}</span>` : ""}
      </a>`).join("")}
    ${moreHref && section.total > section.items.length ? `
      <a class="more" href="${moreHref}" role="option">${name} 목록에서 모두 보기 (${section.total}건) →</a>` : ""}`).join("");
}

/**
 * 띠의 배치 칩 — 일일 배치 스위치와 주간 코드 배치의 다음 실행.
 * 꺼짐은 눈에 띄게 둔다. 검수함이 빈 것이 "다 처리했다"인지 "배치가 꺼졌다"인지 가르는 단서다.
 */
function renderBatchChip(c) {
  const chip = document.getElementById("barBatch");
  if (!chip) return;
  const at = c.codeNextRunAt ? new Date(c.codeNextRunAt) : null;
  const code = c.codeTopics === 0 || !at
    ? "코드 주제 없음"
    : `코드 ${at.getMonth() + 1}/${at.getDate()}(${"일월화수목금토"[at.getDay()]})`;
  // "일일"은 좁은 화면에서 뺀다. 돋보기가 들어가며 375px 띠가 6px 넘쳤다
  chip.innerHTML = `<span><span class="bar-wide">일일&nbsp;</span>배치 ${c.batchEnabled ? "켜짐" : "<b>꺼짐</b>"}</span><span class="bar-chip-more">· ${code}</span>`;
  chip.classList.toggle("off", !c.batchEnabled);
  chip.title = c.batchEnabled ? "일일 배치가 켜져 있습니다. 누르면 배치 현황" : "일일 배치가 꺼져 있어 새 초안이 들어오지 않습니다. 누르면 배치 현황";
  chip.hidden = false;
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
  renderBatchChip(c);

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
