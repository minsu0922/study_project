/* =====================================================================
 * csquiz 문제별 토론 — 화면 한 벌(V21)
 * ---------------------------------------------------------------------
 * [왜 파일을 따로 두나]
 * 쓰는 곳이 둘이다(퀴즈 플레이어, 오답노트). 한쪽에 적고 복사하면 신고나 수정 동작이
 * 언젠가 한쪽만 고쳐진다(report.js와 같은 판단).
 *
 * [사용법]
 *   붙일 자리에 discussionBlock(problemId, open)이 돌려준 HTML을 넣는다. 끝.
 *   open=false면 "토론 N개 보기" 입구만 보이고, 누르면 펼쳐진다 — 문제를 풀기 전 자리에 쓴다.
 *   불러오기와 동작은 이 파일이 맡는다. 호출부가 따로 부를 것이 없다.
 * ===================================================================== */

/** 값은 서버의 CommentReportReason enum과 1:1이다. */
const COMMENT_REPORT_REASONS = [
  ["ABUSE", "욕설·비방"],
  ["SPAM", "광고·도배"],
  ["OFF_TOPIC", "문제와 무관한 글"],
  ["OTHER", "그 밖의 문제"],
];

function discussionBlock(problemId, open) {
  return `<section class="discussion" data-discussion="${problemId}" data-open="${open ? 1 : 0}"></section>`;
}

/* 새로 그려진 블록을 찾아 불러온다. 화면이 블록을 넣을 때마다 따로 부르게 하면 언젠가
 * 빠뜨린 화면에서 토론이 비어 있게 된다 — DOM이 바뀌는 것을 지켜보다가 알아서 붙인다. */
function mountDiscussions() {
  document.querySelectorAll("[data-discussion]:not([data-mounted])").forEach(box => {
    box.dataset.mounted = "1";
    loadDiscussion(box, true);
  });
}
new MutationObserver(mountDiscussions).observe(document.documentElement, { childList: true, subtree: true });
document.addEventListener("DOMContentLoaded", mountDiscussions);

/** reset이면 첫 쪽부터 다시, 아니면 다음 쪽을 이어 붙인다. 상태는 블록이 들고 있다(box._d). */
async function loadDiscussion(box, reset) {
  const page = reset ? 0 : Number(box.dataset.page || 0) + 1;
  try {
    const data = await api(`/api/quiz/${box.dataset.discussion}/comments?page=${page}`);
    if (!reset && box._d) data.comments = box._d.comments.concat(data.comments);
    box._d = data;
    box.dataset.page = String(page);
    renderDiscussion(box);
  } catch (e) {
    box.innerHTML = `<div class="disc-msg error">${escapeHtml(e.message)}</div>`;
  }
}

function renderDiscussion(box) {
  const d = box._d;
  if (box.dataset.open !== "1") {
    // 풀기 전 자리. 글이 없으면 입구도 내지 않는다 — 빈 토론을 열어 볼 이유가 없다.
    box.innerHTML = d.total === 0 ? "" : `
      <button type="button" class="disc-peek" data-disc-open>
        💬 토론 ${d.total}개 보기 <span class="meta">정답 이야기가 있을 수 있습니다</span>
      </button>`;
    return;
  }
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

  if (e.target.closest("[data-disc-open]")) { box.dataset.open = "1"; renderDiscussion(box); return; }
  if (e.target.closest("[data-disc-more]")) { await loadDiscussion(box, false); return; }
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
      await loadDiscussion(box, true);
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
      await api(`/api/me/comments/${form.dataset.id}`, {
        method: "PUT", body: JSON.stringify({ body: form.querySelector("textarea").value }) });
      await loadDiscussion(box, true);
    } else if (form.hasAttribute("data-disc-report-form")) {
      await sendCommentReport(form);
    } else if (form.hasAttribute("data-disc-nick-form")) {
      await api("/api/me/nickname", {
        method: "PUT", body: JSON.stringify({ nickname: form.querySelector("input").value }) });
      const pending = box._pending;
      box._pending = null;
      await postComment(box, pending.parentId, pending.body);
    }
  } catch (err) {
    // 닉네임 폼의 오류는 폼 안에 적는다. 아래 안내 칸에 쓰면 그 칸에 들어 있는 폼이 지워진다.
    const nickError = form.querySelector(".disc-nick-error");
    if (nickError) { nickError.hidden = false; nickError.textContent = err.message; }
    else discMsg(box, err.message, true);
  } finally {
    if (btn.isConnected) btn.disabled = false;
  }
});

/**
 * 댓글 등록. 닉네임이 없으면(DISCUSSION_003) 그 자리에서 닉네임을 받고 같은 글을 다시 보낸다 —
 * 쓴 글을 버리고 다른 화면으로 보내면 돌아와서 다시 써야 한다.
 */
async function postComment(box, parentId, body) {
  try {
    await api("/api/me/comments", { method: "POST", body: JSON.stringify({
      problemId: Number(box.dataset.discussion), parentId: parentId ? Number(parentId) : null, body }) });
    await loadDiscussion(box, true);
  } catch (err) {
    if (err.code !== "DISCUSSION_003") throw err;
    box._pending = { parentId, body };
    const el = box.querySelector(".disc-msg");
    el.hidden = false;
    el.classList.remove("error");
    el.innerHTML = `<form class="disc-form" data-disc-nick-form>
      <label>토론에서 쓸 닉네임을 정해 주세요 (2~12자, 한글·영문·숫자·밑줄)</label>
      <input type="text" required minlength="2" maxlength="12" pattern="[가-힣A-Za-z0-9_]{2,12}">
      <div class="disc-actions"><button type="submit" class="btn-sm">정하고 등록</button></div>
      <div class="disc-nick-error disc-msg error" hidden></div>
    </form>`;
    el.querySelector("input").focus();
  }
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
