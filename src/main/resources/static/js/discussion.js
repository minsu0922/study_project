/* =====================================================================
 * csquiz 문제별 토론 — 화면 한 벌(V21)
 * ---------------------------------------------------------------------
 * [왜 파일을 따로 두나]
 * 쓰는 곳이 둘이다(퀴즈 플레이어, 오답노트). 한쪽에 적고 복사하면 신고나 수정 동작이
 * 언젠가 한쪽만 고쳐진다(report.js와 같은 판단).
 *
 * [사용법]
 *   붙일 자리에 discussionBlock(problemId, mode)가 돌려준 HTML을 넣는다. 끝.
 *   mode=true면 펼친 채로, false나 "closed"면 "토론 N개 보기" 입구만 보이고 누르면 펼쳐진다.
 *   불러오기와 동작은 이 파일이 맡는다. 호출부가 따로 부를 것이 없다.
 * ===================================================================== */

/** 값은 서버의 CommentReportReason enum과 1:1이다. */
const COMMENT_REPORT_REASONS = [
  ["ABUSE", "욕설·비방"],
  ["SPAM", "광고·도배"],
  ["OFF_TOPIC", "문제와 무관한 글"],
  ["OTHER", "그 밖의 문제"],
];

/**
 * @param mode true면 펼친 채로 낸다. false면 풀기 전 입구(글이 있을 때만, 정답 누설 안내와 함께),
 *             "closed"면 접힌 입구를 늘 낸다(이미 푼 문제가 목록으로 여럿 나오는 화면)
 */
function discussionBlock(problemId, mode) {
  const open = mode === true;
  const lazy = open ? "" : (mode === "closed" ? "closed" : "peek");
  return `<section class="discussion" data-discussion="${problemId}" data-open="${open ? 1 : 0}"
                   data-lazy="${lazy}"></section>`;
}

/* 새로 그려진 블록을 찾아 붙인다. 화면이 블록을 넣을 때마다 따로 부르게 하면 언젠가
 * 빠뜨린 화면에서 토론이 비어 있게 된다 — DOM이 바뀌는 것을 지켜보다가 알아서 붙인다.
 *
 * 접힌 블록은 <목록을 읽지 않는다>. 오답노트는 카드 20장이 한 번에 나오는데, 카드마다 목록을
 * 읽으면 세 쪽만 넘겨도 요청 제한(분당 60회)에 걸려 목록 자체가 안 뜬다. 개수만 한 번에 묻는다. */
function mountDiscussions() {
  const lazy = [];
  document.querySelectorAll("[data-discussion]:not([data-mounted])").forEach(box => {
    box.dataset.mounted = "1";
    if (box.dataset.open === "1") loadDiscussion(box);
    else lazy.push(box);
  });
  if (lazy.length) fillLazyCounts(lazy);
}
new MutationObserver(mountDiscussions).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", mountDiscussions);

/** 문제 id → 보이는 댓글 수. 한 화면에서 같은 문제를 다시 묻지 않으려고 둔다. */
const discussionCounts = new Map();
let discussionCountsInFlight = Promise.resolve();

/** 곧 보여 줄 문제들의 댓글 수를 한 번에 읽어 둔다. 플레이어가 세트를 시작할 때 부른다. */
function prefetchDiscussionCounts(problemIds) {
  const unknown = [...new Set(problemIds.map(String))].filter(id => !discussionCounts.has(id));
  if (unknown.length === 0) return discussionCountsInFlight;
  discussionCountsInFlight = discussionCountsInFlight.then(async () => {
    try {
      const counts = await api("/api/quiz/comment-counts?problemIds=" + unknown.join(","));
      unknown.forEach(id => discussionCounts.set(id, counts[id] || 0));
    } catch (e) { /* 개수는 없어도 되는 정보다. 입구는 개수 없이 낸다 */ }
  });
  return discussionCountsInFlight;
}

async function fillLazyCounts(boxes) {
  await prefetchDiscussionCounts(boxes.map(b => b.dataset.discussion));
  boxes.forEach(renderLazy);
}

function renderLazy(box) {
  if (box.dataset.open === "1") return;   // 개수를 기다리는 사이에 펼쳐졌다
  const total = discussionCounts.get(box.dataset.discussion);
  const peek = box.dataset.lazy === "peek";
  // 풀기 전 자리는 글이 없으면 입구도 내지 않는다 — 빈 토론을 열어 볼 이유가 없다.
  if (peek && !total) { box.innerHTML = ""; return; }
  box.innerHTML = `
    <button type="button" class="disc-peek" data-disc-open>
      💬 토론 ${total === undefined ? "" : total + "개 "}보기${peek
        ? ` <span class="meta">정답 이야기가 있을 수 있습니다</span>` : ""}
    </button>`;
}

/** 이미 읽은 쪽은 그대로 두고 다음 쪽을 이어 붙인다. 처음이면 첫 쪽을 읽는다. 상태는 블록이 든다(box._d). */
async function loadDiscussion(box) {
  const page = box._d ? Number(box.dataset.page || 0) + 1 : 0;
  try {
    const data = await api(`/api/quiz/${box.dataset.discussion}/comments?page=${page}`);
    if (box._d) {
      // 방금 쓴 글은 화면에 먼저 붙여 두므로, 다음 쪽에 같은 글이 다시 오면 뺀다.
      const seen = new Set(box._d.comments.map(c => c.id));
      data.comments = box._d.comments.concat(data.comments.filter(c => !seen.has(c.id)));
    }
    setDiscussionData(box, data, page);
  } catch (e) {
    box.innerHTML = `<div class="disc-msg error">${escapeHtml(e.message)}</div>`;
  }
}

/** 읽어 둔 쪽까지를 처음부터 다시 읽는다 — 삭제처럼 자리 표시가 바뀌는 일 뒤에 쓴다. */
async function reloadDiscussion(box) {
  const last = Number(box.dataset.page || 0);
  let merged = null;
  let page = 0;
  for (; page <= last; page++) {
    const data = await api(`/api/quiz/${box.dataset.discussion}/comments?page=${page}`);
    if (merged) data.comments = merged.comments.concat(data.comments);
    merged = data;
    if (!data.hasNext) break;
  }
  setDiscussionData(box, merged, Math.min(page, last));
}

function setDiscussionData(box, data, page) {
  box._d = data;
  box.dataset.page = String(page);
  discussionCounts.set(box.dataset.discussion, data.total);
  renderDiscussion(box);
}

function renderDiscussion(box) {
  const d = box._d;
  box.innerHTML = `
    <h3 class="disc-head">토론 <span class="meta">${d.total}</span></h3>
    <div class="disc-list">${d.comments.length
      ? d.comments.map(c => commentHtml(c, d.canWrite, false)).join("")
      : `<div class="meta">아직 글이 없습니다.</div>`}</div>
    ${d.hasNext ? `<button type="button" class="btn-sm btn-outline" data-disc-more>더 보기</button>` : ""}
    ${writeAreaHtml(d)}
    <div class="disc-msg" hidden></div>`;
}

function writeAreaHtml(d) {
  if (!isLoggedIn()) {
    return `<div class="disc-note"><a href="/login.html">로그인</a>하고 문제를 풀면 참여할 수 있습니다.</div>`;
  }
  if (!d.canWrite) return `<div class="disc-note">문제를 풀면 참여할 수 있습니다.</div>`;
  return formHtml("data-disc-form", "", "", "댓글을 남겨 보세요 (1,000자까지)", "등록", false);
}

function formHtml(kind, id, value, placeholder, label, cancellable) {
  return `<form class="disc-form" ${kind} data-id="${id}">
    <textarea maxlength="1000" rows="2" required placeholder="${placeholder}">${escapeHtml(value)}</textarea>
    <div class="disc-actions">
      <button type="submit" class="btn-sm">${label}</button>
      ${cancellable ? `<button type="button" class="btn-sm btn-outline" data-disc-cancel>취소</button>` : ""}
    </div>
  </form>`;
}

function commentHtml(c, canWrite, isReply) {
  const replies = (c.replies || []).map(r => commentHtml(r, canWrite, true)).join("");
  if (c.status !== "VISIBLE") {
    const text = c.status === "HIDDEN" ? "관리자가 가린 댓글입니다" : "삭제된 댓글입니다";
    return `<div class="disc-item ${isReply ? "reply" : ""} gone" data-comment="${c.id}">
      <div class="meta">${text}</div>${replies}</div>`;
  }
  return `<div class="disc-item ${isReply ? "reply" : ""}" data-comment="${c.id}">
    <div class="disc-meta"><b>${escapeHtml(c.nickname || "탈퇴한 사용자")}</b>
      <span class="meta">${formatDate(c.createdAt)}${c.edited ? " · 수정됨" : ""}</span></div>
    <div class="disc-body">${escapeHtml(c.body)}</div>
    <div class="disc-tools">
      ${canWrite ? `<button type="button" data-disc-reply>답글</button>` : ""}
      ${c.mine ? `<button type="button" data-disc-edit>수정</button>
                  <button type="button" data-disc-delete>삭제</button>` : ""}
      ${isLoggedIn() && !c.mine ? `<button type="button" data-disc-report>신고</button>` : ""}
    </div>
    <div class="disc-slot"></div>
    ${replies}
  </div>`;
}

/** 블록이 들고 있는 목록에서 댓글 하나를 찾는다(답글 포함). */
function findComment(box, id) {
  for (const c of box._d.comments) {
    if (c.id === id) return c;
    const reply = (c.replies || []).find(r => r.id === id);
    if (reply) return reply;
  }
  return null;
}

function discMsg(box, text, isError) {
  const el = box.querySelector(".disc-msg");
  if (!el) return;
  el.hidden = !text;
  el.textContent = text || "";
  el.classList.toggle("error", !!isError);
}

/* ── 누르기 ─────────────────────────────────────────────────────────── */
document.addEventListener("click", async e => {
  const box = e.target.closest("[data-discussion]");
  if (!box) return;
  const item = e.target.closest("[data-comment]");
  const slot = item ? item.querySelector(":scope > .disc-slot") : null;
  const id = item ? Number(item.dataset.comment) : null;

  if (e.target.closest("[data-disc-open]")) { box.dataset.open = "1"; await loadDiscussion(box); return; }
  if (e.target.closest("[data-disc-more]")) { await loadDiscussion(box); return; }
  if (e.target.closest("[data-disc-cancel]")) { e.target.closest(".disc-slot").innerHTML = ""; return; }

  if (e.target.closest("[data-disc-reply]")) {
    slot.innerHTML = formHtml("data-disc-form", id, "", "답글 쓰기", "답글 등록", true);
    slot.querySelector("textarea").focus();
    return;
  }
  if (e.target.closest("[data-disc-edit]")) {
    slot.innerHTML = formHtml("data-disc-edit-form", id, findComment(box, id).body, "", "저장", true);
    slot.querySelector("textarea").focus();
    return;
  }
  if (e.target.closest("[data-disc-report]")) {
    slot.innerHTML = `<form class="disc-form" data-disc-report-form data-id="${id}">
      <select required>
        <option value="">신고 사유를 골라 주세요</option>
        ${COMMENT_REPORT_REASONS.map(([v, l]) => `<option value="${v}">${escapeHtml(l)}</option>`).join("")}
      </select>
      <input type="text" maxlength="500" placeholder="덧붙일 말 (선택)">
      <div class="disc-actions">
        <button type="submit" class="btn-sm">신고 보내기</button>
        <button type="button" class="btn-sm btn-outline" data-disc-cancel>취소</button>
      </div>
    </form>`;
    return;
  }

  // 삭제는 두 번 눌러야 지워진다 — 확인 창은 습관적으로 넘기게 된다(mypage.html 탈퇴와 같은 방식).
  const del = e.target.closest("[data-disc-delete]");
  if (del) {
    if (!del.dataset.armed) {
      del.dataset.armed = "1";
      del.textContent = "정말 삭제";
      setTimeout(() => { delete del.dataset.armed; del.textContent = "삭제"; }, 5000);
      return;
    }
    try {
      await api(`/api/me/comments/${id}`, { method: "DELETE" });
      await reloadDiscussion(box);
    } catch (err) { discMsg(box, err.message, true); }
  }
});

/* ── 보내기 ─────────────────────────────────────────────────────────── */
document.addEventListener("submit", async e => {
  const form = e.target.closest("[data-discussion] form");
  if (!form) return;
  e.preventDefault();
  const box = form.closest("[data-discussion]");
  const btn = form.querySelector('button[type="submit"]');
  btn.disabled = true;   // 응답 전에 두 번 눌려 같은 글이 둘 올라가지 않게

  try {
    if (form.hasAttribute("data-disc-form")) {
      await postComment(box, form.dataset.id, form.querySelector("textarea").value);
    } else if (form.hasAttribute("data-disc-edit-form")) {
      const updated = await api(`/api/me/comments/${form.dataset.id}`, {
        method: "PUT", body: JSON.stringify({ body: form.querySelector("textarea").value }) });
      // 고친 글만 그 자리에서 바꾼다. 목록을 다시 읽으면 "더 보기"로 펼쳐 둔 쪽이 접힌다.
      Object.assign(findComment(box, updated.id), { body: updated.body, edited: updated.edited });
      renderDiscussion(box);
    } else if (form.hasAttribute("data-disc-report-form")) {
      await sendCommentReport(form);
    }
  } catch (err) {
    discMsg(box, err.message, true);
  } finally {
    if (btn.isConnected) btn.disabled = false;
  }
});

/**
 * 댓글 등록.
 *
 * 닉네임은 가입할 때 받는다. 그 전에 만든 계정만 닉네임이 없을 수 있고(DISCUSSION_003),
 * 그때는 마이페이지로 안내한다 — 쓴 글은 칸에 그대로 남아 있어 돌아와서 다시 누르면 된다.
 */
async function postComment(box, parentId, body) {
  try {
    const item = await api("/api/me/comments", { method: "POST", body: JSON.stringify({
      problemId: Number(box.dataset.discussion), parentId: parentId ? Number(parentId) : null, body }) });
    addComment(box, parentId ? Number(parentId) : null, item);
  } catch (err) {
    if (err.code !== "DISCUSSION_003") throw err;
    const el = box.querySelector(".disc-msg");
    el.hidden = false;
    el.classList.add("error");
    el.innerHTML = `닉네임이 없는 계정입니다. <a href="/mypage.html" target="_blank" rel="noopener">마이페이지</a>에서
      닉네임을 정한 뒤 다시 등록해 주세요.`;
  }
}

/**
 * 방금 쓴 글을 화면에 바로 붙인다. 목록을 다시 읽지 않는 이유: 정렬이 시간순이라 새 글은
 * 마지막 쪽에 있다. 첫 쪽만 다시 읽으면 댓글이 20개를 넘는 문제에서 쓴 글이 안 보여,
 * 등록이 안 된 줄 알고 같은 글을 또 올리게 된다.
 */
function addComment(box, parentId, item) {
  item.replies = [];
  const d = box._d;
  if (parentId === null) {
    d.comments.push(item);
  } else {
    // 답글에 단 답글도 서버가 원글 아래로 붙인다. 화면도 같은 묶음에 넣는다.
    const root = d.comments.find(c => c.id === parentId || (c.replies || []).some(r => r.id === parentId));
    if (root) root.replies.push(item);
  }
  d.total += 1;
  discussionCounts.set(box.dataset.discussion, d.total);
  renderDiscussion(box);
}

/** 이미 신고한 글(DISCUSSION_007)은 실패가 아니라 안내다 — 빨갛게 칠하지 않는다. */
async function sendCommentReport(form) {
  const slot = form.closest(".disc-slot");
  try {
    await api("/api/me/comment-reports", { method: "POST", body: JSON.stringify({
      commentId: Number(form.dataset.id),
      reason: form.querySelector("select").value,
      detail: form.querySelector("input").value }) });
    slot.innerHTML = `<div class="meta">신고를 보냈습니다. 확인하겠습니다.</div>`;
  } catch (err) {
    if (err.code !== "DISCUSSION_007") throw err;
    slot.innerHTML = `<div class="meta">${escapeHtml(err.message)}</div>`;
  }
}
