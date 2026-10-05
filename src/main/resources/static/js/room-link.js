/* =====================================================================
 * csquiz 토론방 링크 — 화면 한 벌
 * ---------------------------------------------------------------------
 * 토론은 문제 화면 안이 아니라 토론방(discussion.html)에서 한다. 문제가 보이는 자리에는
 * 그 방으로 가는 링크만 둔다.
 *
 * [왜 파일을 따로 두나]
 * 쓰는 곳이 둘이다(퀴즈 플레이어의 채점 결과, 오답노트의 카드). 한쪽에 적고 복사하면
 * 문구나 개수 표시가 언젠가 한쪽만 고쳐진다(report.js와 같은 판단).
 *
 * [사용법]
 *   붙일 자리에 roomLink(problemId)가 돌려준 HTML을 넣는다. 끝.
 *   글 수는 이 파일이 알아서 읽어 채운다. 호출부가 따로 부를 것이 없다.
 * ===================================================================== */

/** 문제 id → 보이는 글 수. 한 화면에서 같은 문제를 다시 묻지 않으려고 둔다. */
const roomCounts = new Map();
let roomCountsInFlight = Promise.resolve();

/**
 * 곧 보여 줄 문제들의 글 수를 한 번에 읽어 둔다.
 *
 * 링크마다 따로 물으면 오답노트는 카드 20장이 요청 20개를 내고, 빠르게 푸는 사람은
 * 문제마다 하나씩 내서 요청 제한(분당 60회)에 가까워진다.
 */
function prefetchRoomCounts(problemIds) {
  const unknown = [...new Set(problemIds.map(String))].filter(id => !roomCounts.has(id));
  if (unknown.length === 0) return roomCountsInFlight;
  roomCountsInFlight = roomCountsInFlight.then(async () => {
    try {
      const counts = await api("/api/quiz/post-counts?problemIds=" + unknown.join(","));
      unknown.forEach(id => roomCounts.set(id, counts[id] || 0));
    } catch (e) { /* 개수는 없어도 되는 정보다. 링크는 개수 없이 낸다 */ }
  });
  return roomCountsInFlight;
}

function roomLink(problemId) {
  return `<a class="room-link" data-room-link="${problemId}"
             href="/discussion.html?problemId=${problemId}">💬 토론방</a>`;
}

/* 글이 없는 문제는 방도 아직 없다 — 첫 글이 방을 연다. 그래서 "0개"가 아니라 할 일을 적는다. */
function fillRoomLink(el) {
  const total = roomCounts.get(el.dataset.roomLink);
  if (total === undefined) return;
  el.textContent = total > 0 ? `💬 토론방 · 글 ${total}개` : "💬 토론방에 첫 글 쓰기";
}

/* 새로 그려진 링크를 찾아 개수를 채운다. 화면이 링크를 넣을 때마다 따로 부르게 하면 언젠가
 * 빠뜨린 화면에서 개수가 비어 있게 된다 — DOM이 바뀌는 것을 지켜보다가 알아서 채운다. */
async function mountRoomLinks() {
  const links = [...document.querySelectorAll("[data-room-link]:not([data-mounted])")];
  if (links.length === 0) return;
  links.forEach(el => { el.dataset.mounted = "1"; });
  await prefetchRoomCounts(links.map(el => el.dataset.roomLink));
  links.forEach(fillRoomLink);
}
new MutationObserver(mountRoomLinks).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", mountRoomLinks);
