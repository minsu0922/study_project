/* 나란히 검수 — 초안 하나를 넓게 열어 렌더링한 본문 옆에 사실 검수·독자 점검 지적을 둔다
 * (docs/superpowers/specs/2026-11-20-document-reader-check-design.md §5).
 *   GET  /api/admin/llm-documents/{id}
 *   POST /api/admin/llm-documents/{id}/fact-findings/{i}/resolve   {"text"} 고침 · {"note"} 헛경보
 *   POST /api/admin/llm-documents/{id}/reader-findings/{i}/accept
 *   POST /api/admin/llm-documents/{id}/reader-findings/{i}/dismiss {"note"(선택)}
 *   POST /api/admin/llm-documents/{id}/approve · /reject
 * 승인 전 글은 남이 쓴 글이라 innerHTML로 넣을 때 반드시 DOMPurify를 거친다. */
const draftId = param("id");
let draft = null;
let rawView = false;
let tab = "reader";

/* 마크다운에 꽂는 표지. 사용자 영역 문자라 본문에 나올 일이 없고 DOMPurify도 남긴다 */
const HL_START = "", HL_START_END = "", HL_END = "", HL_END_END = "";

if (initAdminPage("llm")) {
  load();
}

async function load() {
  const msg = document.getElementById("rvMessage");
  if (!draftId) {
    msg.innerHTML = `<div class="alert error">초안 번호(id)가 없습니다. 검수함에서 「나란히 검수」로 여세요.</div>`;
    return;
  }
  try {
    draft = await api(`/api/admin/llm-documents/${encodeURIComponent(draftId)}`);
  } catch (e) {
    showError(msg, e);
    return;
  }
  // 검증기가 HTML 태그를 막았으면 원문으로 연다. 렌더링하면 그 태그가 눈에서 사라진다
  if (!rawView && (draft.checks ?? []).some(c => c.severity === "BLOCKING" && c.message.includes("HTML 태그"))) {
    rawView = true;
  }
  // 처리할 사실 검수 지적이 있으면 그 탭부터 연다. 그것이 남아 있으면 승인되지 않는다
  if ((draft.factFindings ?? []).some(f => f.status === "OPEN")) tab = "fact";
  await render();
}

async function render() {
  renderHead();
  const items = collectItems();
  renderPanel(items);
  await renderDoc(items);
}

/* ── 지적 모으기 ─────────────────────────────────────────── */

/** 칠할 지적 목록. 처리하지 않은 것만 본문에 칠한다 */
function collectItems() {
  const items = [];
  (draft.factFindings ?? []).forEach((f, i) => {
    items.push({ id: "f" + i, index: i, source: "fact", color: "fact", label: "사" + (i + 1), quote: f.quote, open: f.status === "OPEN", f });
  });
  (draft.readerFindings ?? []).forEach((f, i) => {
    const color = f.kind === "REPEAT" ? "repeat" : "block";
    items.push({ id: "r" + i, index: i, source: "reader", color, label: "독" + (i + 1), quote: f.quote, open: f.status === "OPEN", f });
  });
  return items;
}

function norm(s) {
  return (s ?? "").replace(/[*`]/g, "").replace(/\s+/g, " ").trim();
}

/** 강조·백틱을 걷어 낸 글로 찾아 원문 자리 [start, end)를 준다(TextSpans.locateOnce와 같은 규칙, 첫 자리) */
function locate(md, target) {
  const needle = norm(target);
  if (!needle) return null;
  let n = "";
  const origin = [];
  let lastSpace = false;
  for (let i = 0; i < md.length; i++) {
    const c = md[i];
    if (c === "*" || c === "`") continue;
    if (/\s/.test(c)) {
      if (lastSpace) continue;
      origin.push(i); n += " "; lastSpace = true;
      continue;
    }
    origin.push(i); n += c; lastSpace = false;
  }
  const at = n.indexOf(needle);
  if (at < 0) return null;
  let start = origin[at], end = origin[at + needle.length - 1] + 1;
  while (end < md.length && (md[end] === "*" || md[end] === "`")) end++;
  while (start > 0 && (md[start - 1] === "*" || md[start - 1] === "`")) start--;
  return [start, end];
}

/** 표지를 꽂으면 안 되는 자리 — mermaid 원문과 그림 줄의 alt는 표지가 들어가면 깨진다 */
function unmarkableRanges(md) {
  const ranges = [];
  for (const m of md.matchAll(/```mermaid[\s\S]*?```/g)) ranges.push([m.index, m.index + m[0].length]);
  for (const m of md.matchAll(/^!\[.*$/gm)) ranges.push([m.index, m.index + m[0].length]);
  return ranges;
}

/** 칠할 자리를 정하고 표지를 꽂은 마크다운을 준다. 다른 지적과 겹치면 앞 지적의 칠을 함께 가리킨다 */
function withMarkers(md, items) {
  const blocked = unmarkableRanges(md);
  const placed = [];
  for (const it of items) {
    it.anchor = null;
    if (!it.open) continue;
    const at = locate(md, it.quote);
    if (!at || blocked.some(([a, b]) => at[0] < b && at[1] > a)) continue;
    const clash = placed.find(p => at[0] < p.at[1] && at[1] > p.at[0]);
    if (clash) { it.anchor = clash.it.id; continue; }
    placed.push({ it, at });
    it.anchor = it.id;
  }
  placed.sort((a, b) => b.at[0] - a.at[0]);
  let out = md;
  for (const { it, at } of placed) {
    out = out.slice(0, at[0]) + HL_START + it.id + HL_START_END + out.slice(at[0], at[1])
        + HL_END + it.id + HL_END_END + out.slice(at[1]);
  }
  return out;
}

/* ── 본문 ───────────────────────────────────────────────── */

async function renderDoc(items) {
  const el = document.getElementById("rvDoc");
  if (rawView || !window.marked || !window.DOMPurify) {
    if (!window.marked || !window.DOMPurify) rawView = true;   // 라이브러리를 못 받으면 원문만 보인다
    items.forEach(it => { it.anchor = null; });
    el.innerHTML = `<pre class="review-raw">${escapeHtml(draft.contentMd)}</pre>`;
    syncAnchors(items);
    return;
  }
  const md = withMarkers(draft.contentMd, items);
  el.innerHTML = DOMPurify.sanitize(marked.parse(md));
  wrapMarkers(el, items);
  syncAnchors(items);
  await renderFigures(el);
  await renderMermaid(el);
}

/** 표지 글자를 빈 span으로 바꾸고, 두 표지 사이의 글자 조각마다 <mark>를 씌운다 */
function wrapMarkers(root, items) {
  const re = /([a-z0-9]+)|([a-z0-9]+)/g;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) {
    if (/[]/.test(walker.currentNode.nodeValue)) nodes.push(walker.currentNode);
  }
  for (const node of nodes) {
    const s = node.nodeValue;
    const frag = document.createDocumentFragment();
    let last = 0;
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(s))) {
      if (m.index > last) frag.append(s.slice(last, m.index));
      const span = document.createElement("span");
      if (m[1]) span.dataset.hlStart = m[1]; else span.dataset.hlEnd = m[2];
      frag.append(span);
      last = re.lastIndex;
    }
    if (last < s.length) frag.append(s.slice(last));
    node.replaceWith(frag);
  }
  const byId = new Map(items.map(it => [it.id, it]));
  for (const start of [...root.querySelectorAll("[data-hl-start]")]) {
    const id = start.dataset.hlStart;
    const end = root.querySelector(`[data-hl-end="${id}"]`);
    const it = byId.get(id);
    if (!end || !it) { start.remove(); end?.remove(); continue; }
    const range = document.createRange();
    range.setStartAfter(start);
    range.setEndBefore(end);
    const texts = [];
    const w = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_TEXT);
    while (w.nextNode()) {
      const t = w.currentNode;
      if (range.intersectsNode(t) && t.nodeValue.trim()) texts.push(t);
    }
    for (const t of texts) {
      const mark = document.createElement("mark");
      mark.className = "hl hl-" + it.color;
      mark.dataset.hl = id;
      t.replaceWith(mark);
      mark.append(t);
    }
    const badge = document.createElement("a");
    badge.className = "hl-num hl-" + it.color;
    badge.href = "#card-" + id;
    badge.id = "mark-" + id;
    badge.textContent = it.label;
    start.replaceWith(badge);
    end.remove();
  }
}

/** 본문에서 자리를 못 찾은 지적은 카드에 그 사실을 적는다 */
function syncAnchors(items) {
  for (const it of items) {
    const where = document.getElementById("where-" + it.id);
    if (!where || !it.open) continue;
    if (it.anchor && document.getElementById("mark-" + it.anchor)) {
      where.innerHTML = `<a href="#mark-${it.anchor}">본문 자리로</a>`;
    } else {
      where.textContent = rawView ? "원문 보기에서는 칠하지 않습니다" : "본문에서 위치를 못 찾음";
    }
  }
}

/** 그림 줄의 SVG를 문서 화면처럼 펼친다. 우리 저장소 파일이지만 주소는 본문이 고르므로 거른다 */
async function renderFigures(root) {
  for (const img of [...root.querySelectorAll('img[src^="/images/docs/"][src$=".svg"]')]) {
    try {
      const res = await fetch(img.getAttribute("src"));
      if (!res.ok) continue;
      const box = document.createElement("div");
      box.className = "doc-diagram doc-figure";
      box.innerHTML = DOMPurify.sanitize(await res.text(), { USE_PROFILES: { svg: true } });
      if (!box.querySelector("svg")) continue;
      const p = img.parentElement;
      const alone = p && p.tagName === "P" && p.childElementCount === 1 && p.textContent.trim() === "";
      (alone ? p : img).replaceWith(box);
    } catch (e) {
      // 그림 하나 때문에 검수를 막지 않는다
    }
  }
}

async function renderMermaid(root) {
  const blocks = [...root.querySelectorAll("pre > code.language-mermaid")];
  if (blocks.length === 0) return;
  try {
    await loadDraftMermaid();
  } catch (e) {
    return;   // 못 받으면 코드 블록이 그대로 남는다
  }
  const t = document.documentElement.dataset.theme;
  const dark = t === "dark" || (t !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: dark ? "dark" : "neutral" });
  for (const code of blocks) {
    const source = code.textContent;
    if (!(await mermaid.parse(source, { suppressErrors: true }))) continue;
    const id = "review-diagram-" + (++draftDiagramSeq);
    try {
      const figure = document.createElement("figure");
      figure.className = "doc-diagram";
      figure.innerHTML = (await mermaid.render(id, source)).svg;
      code.parentElement.replaceWith(figure);
    } catch (e) {
      document.getElementById("d" + id)?.remove();
    }
  }
}

/* ── 머리 ───────────────────────────────────────────────── */

function renderHead() {
  const openFacts = (draft.factFindings ?? []).filter(f => f.status === "OPEN").length;
  const blocking = (draft.checks ?? []).filter(c => c.severity === "BLOCKING");
  const warnings = (draft.checks ?? []).filter(c => c.severity !== "BLOCKING");
  const pending = draft.status === "PENDING";
  const cannotApprove = draft.blocked || openFacts > 0;
  const statusText = { PENDING: "검수 대기", APPROVED: "승인됨", REJECTED: "거절됨" }[draft.status] ?? draft.status;
  document.getElementById("rvHead").innerHTML = `
    <div class="review-head-top">
      <div>
        <span class="badge gray">#${draft.id}</span>
        <span class="badge gray">${escapeHtml(draft.domainName)}</span>
        <span class="badge ${pending ? "gray" : draft.status === "APPROVED" ? "green" : "red"}">${statusText}</span>
        <span class="meta">${escapeHtml(draft.model)} · ${draft.contentLength.toLocaleString()}자 · /${escapeHtml(draft.slug)}</span>
      </div>
      <button class="btn-outline" id="rvRawToggle">${rawView ? "렌더링해 보기" : "원문 보기"}</button>
    </div>
    <h1 class="review-title">${escapeHtml(draft.title)}</h1>
    ${blocking.length ? `<div class="alert error"><b>승인을 막는 문제 ${blocking.length}건</b><ul>${blocking.map(c => `<li>${escapeHtml(c.message)}</li>`).join("")}</ul></div>` : ""}
    ${warnings.length ? `<div class="meta">${warnings.map(c => `${icon("alert")} ${escapeHtml(c.message)}`).join("<br>")}</div>` : ""}
    ${pending ? `
    <div class="row-actions" style="margin-top:10px">
      <button id="rvApprove" ${cannotApprove ? "disabled" : ""}>승인 → 문서 등록</button>
      ${openFacts > 0 ? `<span class="meta">사실 검수 지적 ${openFacts}건 처리 필요 · 독자 점검은 승인을 막지 않습니다</span>` : ""}
      <button class="danger" id="rvRejectOpen">거절</button>
    </div>
    <div id="rvRejectForm" hidden style="margin-top:8px">
      <input id="rvRejectReason" type="text" maxlength="500" style="width:100%" placeholder="거절 사유 (선택)">
      <div class="row-actions" style="margin-top:6px">
        <button class="danger" id="rvRejectConfirm">거절 확정</button>
        <button id="rvRejectCancel">취소</button>
      </div>
    </div>` : draft.status === "APPROVED"
      ? `<div class="meta">승인됨 → <a href="/document.html?slug=${encodeURIComponent(draft.slug)}">문서 보기</a></div>`
      : `<div class="meta">거절됨${draft.rejectReason ? " — " + escapeHtml(draft.rejectReason) : ""}</div>`}`;

  document.getElementById("rvRawToggle").onclick = () => { rawView = !rawView; render(); };
  if (!pending) return;
  document.getElementById("rvApprove").onclick = e => act(e.currentTarget, `/approve`, null, "승인했습니다.");
  document.getElementById("rvRejectOpen").onclick = () => { document.getElementById("rvRejectForm").hidden = false; };
  document.getElementById("rvRejectCancel").onclick = () => { document.getElementById("rvRejectForm").hidden = true; };
  document.getElementById("rvRejectConfirm").onclick = e => {
    const reason = document.getElementById("rvRejectReason").value.trim();
    act(e.currentTarget, `/reject`, reason ? { reason } : {}, "거절했습니다.");
  };
}

/* ── 지적 패널 ──────────────────────────────────────────── */

function renderPanel(items) {
  const facts = items.filter(it => it.source === "fact");
  const readers = items.filter(it => it.source === "reader");
  const openFacts = facts.filter(it => it.open), openReaders = readers.filter(it => it.open);
  const done = items.filter(it => !it.open);
  const shown = tab === "fact" ? openFacts : tab === "reader" ? openReaders : done;
  const empty = tab === "fact"
    ? (draft.factFindings == null ? "사실 검수를 안 돌린 초안입니다. 사실 확인은 직접 읽어서 하세요." : "남은 사실 검수 지적이 없습니다.")
    : tab === "reader"
      ? (draft.readerFindings == null ? "독자 점검을 안 돌린 초안입니다." : "남은 독자 점검 지적이 없습니다.")
      : "처리한 지적이 없습니다.";
  const panel = document.getElementById("rvPanel");
  panel.innerHTML = `
    <div class="review-tabs" role="tablist">
      ${tabButton("fact", `사실 검수 ${openFacts.length}`)}
      ${tabButton("reader", `독자 점검 ${openReaders.length}`)}
      ${tabButton("done", `처리함 ${done.length}`)}
    </div>
    <p class="meta review-legend"><mark class="hl hl-fact">사실 검수</mark> <mark class="hl hl-block">독자 막힘</mark> <mark class="hl hl-repeat">독자 되풀이</mark></p>
    ${shown.length ? `<ol class="review-cards">${shown.map(cardOf).join("")}</ol>` : `<p class="meta">${empty}</p>`}`;
  panel.querySelectorAll("[data-tab]").forEach(b => { b.onclick = () => { tab = b.dataset.tab; render(); }; });
  panel.querySelectorAll("[data-act]").forEach(b => { b.onclick = () => onCardAction(b); });
}

function tabButton(key, label) {
  return `<button role="tab" aria-selected="${tab === key}" class="${tab === key ? "" : "btn-outline"}" data-tab="${key}">${label}</button>`;
}

function cardOf(it) {
  return it.source === "fact" ? factCard(it) : readerCard(it);
}

function factCard(it) {
  const f = it.f;
  const pending = draft.status === "PENDING" && it.open;
  return `
    <li class="review-card hl-${it.color}" id="card-${it.id}">
      <div class="review-card-head">
        <a class="hl-num hl-${it.color}" href="#mark-${it.id}">${it.label}</a>
        <span class="badge gray">${escapeHtml(f.label)}</span>
        <span class="badge gray">${f.confident ? "확신" : "의심"}</span>
        <span class="meta review-where" id="where-${it.id}"></span>
      </div>
      <p class="review-quote">「${escapeHtml(f.quote ?? "")}」</p>
      ${f.reason ? `<p>이유: ${escapeHtml(f.reason)}</p>` : ""}
      ${f.correction ? `<p>바로잡으면: ${escapeHtml(f.correction)}</p>` : ""}
      ${/^https?:\/\//.test(f.sourceUrl ?? "") ? `<p class="meta">출처: <a href="${escapeHtml(f.sourceUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(f.sourceUrl)}</a></p>` : ""}
      ${f.resolution ? `<p class="meta"><span class="badge ${f.status === "FIXED" ? "green" : "gray"}">${f.status === "FIXED" ? "고침" : "헛경보"}</span> ${escapeHtml(f.resolution)}</p>` : ""}
      ${pending ? `
      <div class="row-actions">
        ${f.paragraph != null ? `<button class="btn-outline" data-act="fact-fix-open" data-i="${it.index}">고침 — 문단 고치기</button>` : `<span class="meta">지적된 문장이 본문에 없습니다</span>`}
        <button class="btn-outline" data-act="fact-false-open" data-i="${it.index}">헛경보</button>
      </div>
      <div id="factFix-${it.index}" hidden>
        <textarea id="factFixText-${it.index}" rows="6" maxlength="20000" style="width:100%">${escapeHtml(f.paragraph ?? "")}</textarea>
        <div class="row-actions"><button data-act="fact-fix" data-i="${it.index}">고친 문단 적용</button></div>
      </div>
      <div id="factFalse-${it.index}" hidden>
        <input id="factFalseNote-${it.index}" type="text" maxlength="300" style="width:100%" placeholder="왜 틀린 지적인가">
        <div class="row-actions"><button data-act="fact-false" data-i="${it.index}">헛경보로 처리</button></div>
      </div>` : ""}
    </li>`;
}

const OP_NAMES = { REPLACE: "바꾸기", INSERT_AFTER: "뒤에 넣기", DELETE: "지우기", NEED_HUMAN: "사람 확인 필요" };

function readerCard(it) {
  const f = it.f;
  const pending = draft.status === "PENDING" && it.open;
  return `
    <li class="review-card hl-${it.color}" id="card-${it.id}">
      <div class="review-card-head">
        <a class="hl-num hl-${it.color}" href="#mark-${it.id}">${it.label}</a>
        <span class="badge gray">${escapeHtml(f.kindLabel)} · ${escapeHtml(f.typeLabel)}</span>
        <span class="badge gray">${f.weight === "STOP" ? "멈춤" : "멈칫"}</span>
        <span class="meta review-where" id="where-${it.id}"></span>
      </div>
      <p class="meta">${escapeHtml(f.section ?? "")}</p>
      <p class="review-quote">「${escapeHtml(f.quote ?? "")}」</p>
      <p>${escapeHtml(f.reason ?? "")}</p>
      ${f.earlier ? `<p class="meta">앞에서 읽은 문장: ${escapeHtml(f.earlier)}</p>` : ""}
      ${suggestionBox(f)}
      ${f.resolution ? `<p class="meta"><span class="badge ${f.status === "ACCEPTED" ? "green" : "gray"}">${f.status === "ACCEPTED" ? "수락" : "넘김"}</span> ${escapeHtml(f.resolution)}</p>` : ""}
      ${pending ? `
      <div class="row-actions">
        ${f.acceptable ? `<button data-act="reader-accept" data-i="${it.index}">제안 수락</button>` : ""}
        <button class="btn-outline" data-act="reader-dismiss-open" data-i="${it.index}">넘김</button>
      </div>
      <div id="readerDismiss-${it.index}" hidden>
        <input id="readerDismissNote-${it.index}" type="text" maxlength="300" style="width:100%" placeholder="넘기는 이유 (선택)">
        <div class="row-actions"><button data-act="reader-dismiss" data-i="${it.index}">넘기기</button></div>
      </div>` : ""}
    </li>`;
}

function suggestionBox(f) {
  const op = f.op ?? "NEED_HUMAN";
  let rows = "";
  if (op === "REPLACE") {
    const [a, b] = diffMark(f.target, f.text);
    rows = `<div><span class="review-lab">지금</span>${a}</div><div><span class="review-lab">제안</span>${b}</div>`;
  } else if (op === "INSERT_AFTER") {
    rows = `<div><span class="review-lab">지금</span>${escapeHtml(f.target)}</div><div><span class="review-lab">제안</span>${escapeHtml(f.target)} <ins>${escapeHtml(f.text)}</ins></div>`;
  } else if (op === "DELETE") {
    rows = `<div><span class="review-lab">지금</span><del>${escapeHtml(f.target)}</del></div><div><span class="review-lab">제안</span><em>(지움)</em></div>`;
  }
  const why = op !== "NEED_HUMAN" && !f.acceptable && f.status === "OPEN"
    ? `<p class="meta">지금 본문에는 바로 넣을 수 없습니다 — 문장이 바뀌었거나 제목·표·코드 같은 자리입니다.</p>` : "";
  return `<div class="review-sug ${op === "NEED_HUMAN" ? "need-human" : ""}">
      <div class="review-sug-op">제안 · ${OP_NAMES[op] ?? op}</div>${rows}
      ${f.note ? `<p class="meta">${escapeHtml(f.note)}</p>` : ""}${why}</div>`;
}

/** 글자 단위 비교 — 지운 글자는 del, 새 글자는 ins. 문장 하나 길이라 표를 다 채워도 가볍다 */
function diffMark(a, b) {
  a = a ?? ""; b = b ?? "";
  const n = a.length, m = b.length;
  const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
    dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
  let i = 0, j = 0, x = "", y = "", dx = "", iy = "";
  const flush = () => {
    if (dx) { x += `<del>${escapeHtml(dx)}</del>`; dx = ""; }
    if (iy) { y += `<ins>${escapeHtml(iy)}</ins>`; iy = ""; }
  };
  while (i < n && j < m) {
    if (a[i] === b[j]) { flush(); x += escapeHtml(a[i]); y += escapeHtml(b[j]); i++; j++; }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { dx += a[i++]; }
    else { iy += b[j++]; }
  }
  dx += a.slice(i); iy += b.slice(j);
  flush();
  return [x, y];
}

async function onCardAction(btn) {
  const i = btn.dataset.i;
  switch (btn.dataset.act) {
    case "fact-fix-open": toggle(`factFix-${i}`); return;
    case "fact-false-open": toggle(`factFalse-${i}`); return;
    case "reader-dismiss-open": toggle(`readerDismiss-${i}`); return;
    case "fact-fix":
      return act(btn, `/fact-findings/${i}/resolve`, { text: document.getElementById(`factFixText-${i}`).value });
    case "fact-false":
      return act(btn, `/fact-findings/${i}/resolve`, { note: document.getElementById(`factFalseNote-${i}`).value });
    case "reader-accept":
      return act(btn, `/reader-findings/${i}/accept`, null);
    case "reader-dismiss": {
      const note = document.getElementById(`readerDismissNote-${i}`).value.trim();
      return act(btn, `/reader-findings/${i}/dismiss`, note ? { note } : {});
    }
  }
}

function toggle(id) {
  const el = document.getElementById(id);
  el.hidden = !el.hidden;
}

/** 처리 요청을 보내고 초안을 다시 받아 전부 다시 그린다. 처리 결과가 본문·승인 버튼을 함께 바꾼다 */
async function act(btn, path, body, okText) {
  const msg = document.getElementById("rvMessage");
  btn.disabled = true;
  try {
    await api(`/api/admin/llm-documents/${draft.id}${path}`,
      body == null ? { method: "POST" } : { method: "POST", body: JSON.stringify(body) });
    draft = await api(`/api/admin/llm-documents/${draft.id}`);
    msg.innerHTML = okText ? `<div class="alert info">${escapeHtml(okText)}</div>` : "";
    if (okText) refreshAdminBadges();
    await render();
  } catch (e) {
    btn.disabled = false;
    showError(msg, e);
    msg.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }
}
