/* =====================================================================
 * admin-table.js — 관리 콘솔의 표 목록 한 벌 (2026-09-06 콘솔 개편)
 * ---------------------------------------------------------------------
 * [무엇을 모았나] 목록 화면이 되풀이하던 여섯 가지다.
 *   ① 조회(페이지·필터) ② 머리글+행 ③ 빈 상태 ④ 클릭 위임 ⑤ 전체 건수 ⑥ 페이저
 * 화면은 <열이 무엇인가>와 <행을 누르면 무엇을 하는가>만 적는다.
 *
 * [왜 모았나] 여섯 중 다섯은 화면마다 같은 코드였는데, 같은 코드가 여러 벌이면 한 곳을
 * 고칠 때 나머지를 잊는다. 실제로 어긋난 자리가 둘 있었다 —
 *   · 문제 목록에는 "결과 0건" 안내가 있는데 문서 목록에는 없었다(머리글만 남아
 *     로딩 중인지 없는 건지 알 수 없었다).
 *   · 문서 목록은 size=100으로 통째로 받고 페이저가 아예 없었다. 문서가 100편을
 *     넘기는 날 조용히 잘린다.
 *
 * [무엇을 안 모았나]
 *   · <검수 화면(llm.html)>은 카드 목록이다. 한 건마다 경고 상자·절 목록·반려 사유가
 *     붙어 표로 눕히면 그 정보가 갈 곳이 없다. 페이저와 필터만 나눠 쓴다.
 *   · <제보 화면>도 같은 이유다. 제보는 본문이 긴 글이라 칸에 넣으면 잘린다.
 *
 * [일괄 처리를 넣지 않았다]
 * 계획에는 체크박스 + 일괄 삭제가 있었는데 빼기로 했다. 문제·문서를 여러 개 골라 한 번에
 * 지우는 것은 실제 작업 흐름이 아니고(검수의 일괄 승인은 20장을 훑는 흐름이라 다르다),
 * 지금 삭제는 armedAction(두 번 눌러 확인)라는 <더 안전한 장치>를 쓰고 있다. 그것을
 * confirm 한 번으로 바꾸는 것은 되돌릴 수 없는 동작을 더 쉽게 만드는 일이다.
 * 쓰지 않을 기능을 미리 만들면 그것이 깨져도 아무도 모른다.
 * ===================================================================== */

/** 마운트 지점별 상태. 다시 그릴 때 같은 조건(페이지·설정)을 쓰려고 들고 있는다. */
const adminTableState = new Map();

/**
 * 표 목록을 그린다.
 *
 * @param mountSelector 표를 넣을 자리(예 {@code "#docList"})
 * @param config
 *   {@code url}       — 페이지·필터 없는 기본 주소. 예 {@code "/api/admin/problems"}<br>
 *   {@code columns}   — {@code [{ head, cell(row), width?, wrap?, className? }]}<br>
 *   {@code filters?}  — {@code () => "&domain=OS"} 꼴의 쿼리 조각을 돌려주는 함수<br>
 *   {@code onRowClick?} — {@code (row, action) => void}. action은 눌린 버튼의
 *                       {@code data-action} 값이고, 행 빈 곳을 누르면 null이다<br>
 *   {@code emptyText?} — 결과가 0건일 때의 문구<br>
 *   {@code pageSize?}  — 서버에 넘길 size. 안 주면 서버 기본값
 * @param opts {@code { page }} — 넘기면 그 쪽으로, 안 넘기면 <b>보던 쪽을 지킨다</b>.
 *   필터를 바꿀 때는 반드시 {@code { page: 0 }}을 넘긴다 — 3쪽을 보다가 필터를 좁히면
 *   결과가 1쪽뿐인데 3쪽을 달라고 해서 빈 화면이 뜬다.
 */
async function renderAdminTable(mountSelector, config, opts = {}) {
  const prev = adminTableState.get(mountSelector);
  const page = opts.page !== undefined ? opts.page : (prev ? prev.page : 0);
  adminTableState.set(mountSelector, { config, page });

  const mount = document.querySelector(mountSelector);
  if (!mount) return;

  const query = `?page=${page}`
    + (config.pageSize ? `&size=${config.pageSize}` : "")
    + (config.filters ? config.filters() : "");

  let data;
  try {
    data = await api(config.url + query);
  } catch (e) {
    // 목록이 안 뜨는 것과 0건인 것은 다른 상태다. 뭉개면 "왜 비었지"를 알 수 없다.
    mount.innerHTML = `<div class="alert error">목록을 불러오지 못했습니다: ${escapeHtml(e.message)}</div>`;
    return;
  }

  const rows = data.content ?? [];

  // 빈 상태를 <반드시> 그린다. 머리글만 남으면 로딩 중인지 결과가 없는 건지 알 수 없다.
  // 필터가 있는 화면에서는 "필터를 지우면 전체가 보인다"까지 말해 준다 —
  // 0건의 원인이 대개 필터인데, 그 사실이 화면 어디에도 없으면 데이터가 없는 줄 안다.
  const emptyText = config.emptyText
    ?? (config.filters
      ? "조건에 맞는 항목이 없습니다 — 필터를 지우면 전체가 보입니다."
      : "항목이 없습니다.");
  const empty = `<tr><td colspan="${config.columns.length}" class="meta"
      style="padding:18px;text-align:center">${escapeHtml(emptyText)}</td></tr>`;

  mount.innerHTML = `
    <div class="table-wrap">
      <table class="grid">
        <thead><tr>
          ${config.columns.map(c =>
            `<th${c.width ? ` style="width:${c.width}"` : ""}>${escapeHtml(c.head)}</th>`).join("")}
        </tr></thead>
        <tbody>${rows.length === 0 ? empty : rows.map((r, i) => `
          <tr class="${config.onRowClick ? "click-row" : ""}" data-i="${i}">
            ${config.columns.map(c =>
              `<td class="${c.wrap ? "wrap " : ""}${c.className ?? ""}">${c.cell(r)}</td>`).join("")}
          </tr>`).join("")}</tbody>
      </table>
    </div>
    <div class="pager-row">
      <span class="meta">전체 ${data.totalElements ?? rows.length}건</span>
      <span class="spacer"></span>
      ${renderAdminPager(data)}
    </div>`;

  wireAdminTable(mountSelector, mount, config, rows);
}

/**
 * 표에 동작을 붙인다.
 *
 * <p><b>클릭은 표 하나에 위임한다.</b> 행마다 {@code onclick="..."} 문자열을 심는 방식은
 * 값이 HTML 문자열 안에서 <b>코드가 되는</b> 구조라, escapeHtml(HTML용 이스케이프)로
 * 자바스크립트 문자열을 막으려는 잘못된 코드가 실제로 있었다(문서 목록의 slug).
 * 위임하면 값이 코드로 해석될 자리 자체가 없어진다.
 *
 * <p><b>행 객체를 인덱스로 되찾는다.</b> id를 data-*에 넣고 다시 찾는 방법도 있지만,
 * 그러면 화면마다 "무엇이 id인가"를 렌더러에 알려 줘야 한다. 화면이 필요한 것은 대개
 * id 하나가 아니라 <행 전체>다(문서 목록은 slug도 쓴다). 인덱스는 방금 그린 배열의
 * 자리라 틀릴 수가 없다.
 *
 * <p><b>버튼을 먼저 판별한다.</b> 순서를 뒤집으면 삭제를 눌렀는데 수정 폼이 열린다.
 */
function wireAdminTable(mountSelector, mount, config, rows) {
  if (config.onRowClick) {
    mount.querySelector("table").onclick = e => {
      const tr = e.target.closest(".click-row");
      if (!tr) return;
      const row = rows[Number(tr.dataset.i)];
      if (!row) return;
      const btn = e.target.closest("button[data-action]");
      config.onRowClick(row, btn ? btn.dataset.action : null, btn);
    };
  }

  mount.querySelectorAll("[data-page]").forEach(btn => {
    btn.addEventListener("click", () => {
      const st = adminTableState.get(mountSelector);
      renderAdminTable(mountSelector, st.config, { page: Number(btn.dataset.page) });
    });
  });
}

/**
 * 같은 조건으로 다시 그린다. 삭제 뒤에 부른다.
 *
 * <p>마지막 한 건을 지워 그 쪽이 비면 <b>한 쪽 앞으로 물러난다</b>. 안 그러면 "3 / 3"에서
 * 마지막을 지운 사람이 빈 화면을 보고 목록이 사라진 줄 안다.
 */
async function adminTableReload(mountSelector) {
  const st = adminTableState.get(mountSelector);
  if (!st) return;
  await renderAdminTable(mountSelector, st.config);

  const rows = document.querySelectorAll(`${mountSelector} tbody .click-row`);
  const now = adminTableState.get(mountSelector);
  if (rows.length === 0 && now.page > 0) {
    await renderAdminTable(mountSelector, now.config, { page: now.page - 1 });
  }
}

/**
 * 페이저 한 벌 — 표와 카드 목록이 함께 쓴다.
 *
 * <p>쪽이 하나면 통째로 안 그린다. 누를 수 없는 버튼 둘이 떠 있으면 "다음이 있나?"를
 * 매번 확인하게 된다.
 *
 * <p>마크업만 만들고 <b>동작은 안 붙인다</b>. 한 화면에 목록이 둘인 경우가 있어
 * (검수 화면의 문제 초안·문서 초안) "어느 목록의 쪽을 넘기는가"는 부르는 쪽이 정해야 한다.
 */
function renderAdminPager(data) {
  if (!data.totalPages || data.totalPages <= 1) return "";
  return `
    <button class="btn-outline" data-page="${data.page - 1}" ${data.page === 0 ? "disabled" : ""}>이전</button>
    <span class="meta">${data.page + 1} / ${data.totalPages}</span>
    <button class="btn-outline" data-page="${data.page + 1}" ${data.hasNext ? "" : "disabled"}>다음</button>`;
}

/**
 * 셀렉트·입력 묶음을 쿼리 조각으로. <b>빈 값은 안 넘긴다</b> — 서버가 조건을 건너뛴다.
 *
 * @param map {@code { 파라미터이름: 요소id }} — 예 {@code { domain: "fpDomain" }}
 */
function filterQuery(map) {
  return Object.entries(map)
    .map(([key, id]) => {
      const el = document.getElementById(id);
      const v = el ? el.value : "";
      return v ? `&${key}=${encodeURIComponent(v)}` : "";
    })
    .join("");
}
