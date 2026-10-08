/* =====================================================================
 * bookmark.js — 북마크 버튼 한 벌 (V33)
 * ---------------------------------------------------------------------
 * 버튼이 놓이는 자리가 여럿이다(플레이어의 채점 결과 · 오답노트 · 문서 화면).
 * 화면마다 따로 만들면 문구와 동작이 어긋나므로 report.js와 같은 방식으로 모은다:
 * 마크업은 함수가 만들고, 클릭은 document에서 한 번만 받는다.
 * ===================================================================== */

/**
 * 버튼 마크업. 비로그인이면 빈 문자열 — 담을 계정이 없다.
 *
 * @param type "PROBLEM" 또는 "DOCUMENT"
 */
function bookmarkButton(type, id) {
  if (!isLoggedIn()) return "";
  return `<button type="button" class="bookmark-btn" data-bookmark="${type}:${id}"
            aria-pressed="false">☆ 북마크</button>`;
}

/* 별 모양과 글자를 함께 바꾼다 — 색만 바꾸면 담겼는지 색으로만 알아야 한다. */
function paintBookmark(btn, on) {
  btn.setAttribute("aria-pressed", String(on));
  btn.textContent = on ? "★ 북마크됨" : "☆ 북마크";
}

/**
 * 방금 그린 버튼들에 지금 상태를 채운다. 버튼을 그린 쪽이 부른다.
 * 버튼마다 묻지 않고 한 번에 묻는다 — 오답노트는 한 화면에 버튼이 스무 개다.
 * 실패하면 버튼은 빈 별로 남는다. 눌러서 담는 것은 그대로 된다.
 */
async function hydrateBookmarks(root = document) {
  if (!isLoggedIn()) return;
  const buttons = [...root.querySelectorAll("[data-bookmark]")];
  if (buttons.length === 0) return;
  const ids = { PROBLEM: [], DOCUMENT: [] };
  buttons.forEach(b => {
    const [type, id] = b.dataset.bookmark.split(":");
    ids[type].push(id);
  });
  try {
    const state = await api(`/api/me/bookmarks/state?problemIds=${ids.PROBLEM.join(",")}`
      + `&documentIds=${ids.DOCUMENT.join(",")}`);
    const on = new Set([
      ...state.problemIds.map(id => "PROBLEM:" + id),
      ...state.documentIds.map(id => "DOCUMENT:" + id),
    ]);
    buttons.forEach(b => paintBookmark(b, on.has(b.dataset.bookmark)));
  } catch (e) { /* 위 주석 */ }
}

document.addEventListener("click", async e => {
  const btn = e.target.closest("[data-bookmark]");
  if (!btn) return;
  const [type, id] = btn.dataset.bookmark.split(":");
  const on = btn.getAttribute("aria-pressed") === "true";
  btn.disabled = true;   // 응답 전에 또 누르면 담기와 빼기가 엇갈려 나간다
  try {
    await api(`/api/me/bookmarks/${type}/${id}`, { method: on ? "DELETE" : "PUT" });
    paintBookmark(btn, !on);
  } catch (err) {
    btn.title = "저장하지 못했습니다: " + err.message;
  } finally {
    btn.disabled = false;
  }
});
