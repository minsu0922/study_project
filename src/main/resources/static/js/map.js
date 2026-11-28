/* 백엔드 기술 지도 — GET /api/map (공개, 로그인했으면 내 진도가 실린다).
 * 넓은 화면은 가운데 「백엔드」에서 분야가 원 둘레로 뻗고, 분야마다 바깥쪽 부채꼴에 주제 점이 선다.
 * 좁은 화면은 같은 데이터를 분야별 <details> 목록으로 그린다. 둘 다 그려 두고 CSS가 하나만 보인다.
 * 라이브러리 없이 SVG를 직접 그린다 — 점 백 개 남짓에 좌표 계산이 전부라 들일 이유가 없다. */

/* 좌표는 SVG 단위(가운데가 0,0). 화면 크기는 viewBox가 맞춘다. */
const CENTER_R = 54;      // 「백엔드」 원
const DOMAIN_RING = 150;  // 분야 원이 서는 둘레
const DOMAIN_R = 44;      // 분야 원. 여섯 글자(데이터베이스)가 12px로 들어가는 크기
const FAN_START = 236;    // 주제 점 첫 줄. 분야 원 바깥(194)과 점 반지름·여백을 띄운 값
const ROW_GAP = 26;       // 줄 사이. 점 지름 16 + 틈 10
const DOT_R = 8;
const DOT_STEP = 23;      // 한 줄 안에서 점 사이 호 길이

/* 상태 이름 — 패널·범례·aria-label이 함께 쓴다. open은 비로그인용 하나짜리 모양이다 */
const STATE_LABEL = {
  none: "준비 중", unsolved: "아직 안 풂", partial: "푸는 중", done: "다 맞힘", open: "문서 있음",
};

/** 주제 id → { topic, domain, state }. 점·목록 단추가 data-id로 찾아온다 */
const topicIndex = new Map();
let loggedIn = false;

/**
 * 주제 하나의 진도 상태. 조건은 설계 표 그대로다.
 * 풀어 봤는데 하나도 못 맞혔으면 partial이다 — 손을 댄 주제와 안 댄 주제를 가르려는 것이다.
 */
function topicState(t) {
  if (t.documents.length === 0) return "none";
  if (t.attempted === 0 || t.problemTotal === 0) return "unsolved";
  if (t.solved === t.problemTotal) return "done";
  return "partial";
}

/** 칠할 모양. 비로그인은 진도가 전부 0이라 넷으로 나누면 거짓이 된다 — 문서 있음/준비 중 둘만 쓴다 */
function lookOf(state) {
  if (loggedIn || state === "none") return state;
  return "open";
}

loadMap();

async function loadMap() {
  const board = document.getElementById("mapBoard");
  // 알림·로딩은 #message에 둔다. 판(board)은 좁은 화면에서 숨어 있어 거기 쓰면 폰에서 안 보인다
  const message = document.getElementById("message");
  showLoading(message);
  try {
    const data = await api("/api/map");
    // 만료 토큰이면 api()가 지우고 비로그인으로 답한다. 그래서 부른 뒤에 판정한다
    loggedIn = isLoggedIn();
    const domains = data.domains || [];
    if (domains.length === 0) {
      showEmpty(message, "아직 지도에 올릴 주제가 없어요.");
      return;
    }
    domains.forEach(d => d.topics.forEach(t => {
      topicIndex.set(String(t.id), { topic: t, domain: d, state: topicState(t) });
    }));
    renderLegend();
    board.innerHTML = svgHtml(domains) + `<div class="map-tip" id="mapTip" hidden></div>`;
    document.getElementById("mapList").innerHTML = listHtml(domains);
    renderPanel(null);
    wire();
  } catch (e) {
    showErrorAlert(message, e, "지도를 불러오지 못했습니다");
  } finally {
    clearLoading(message);
  }
}

/* ── 범례·안내 ── */
function renderLegend() {
  const looks = loggedIn ? ["done", "partial", "unsolved", "none"] : ["open", "none"];
  const el = document.getElementById("mapLegend");
  el.innerHTML = looks.map(k =>
    `<span class="map-legend-item"><span class="map-swatch map-dot--${k}"></span>${STATE_LABEL[k]}</span>`).join("");
  el.hidden = false;
  if (!loggedIn) {
    document.getElementById("message").innerHTML =
      `<div class="alert info">로그인하면 진도가 칠해집니다 <a href="/login.html">로그인하기</a></div>`;
  }
}

/* ── 넓은 화면: SVG ── */
function polar(r, deg) {
  const a = deg * Math.PI / 180;
  return [r * Math.cos(a), r * Math.sin(a)];
}
const f1 = n => n.toFixed(1);

/** 분야 하나의 점 좌표. 안쪽 줄부터 채운다 — 바깥 줄일수록 호가 길어 더 많이 들어간다 */
function fanLayout(count, centerDeg, halfSpanDeg) {
  const spanRad = halfSpanDeg * 2 * Math.PI / 180;
  const points = [];
  let row = 0;
  while (points.length < count) {
    const r = FAN_START + row * ROW_GAP;
    const capacity = Math.max(1, Math.floor(spanRad * r / DOT_STEP) + 1);
    const n = Math.min(capacity, count - points.length);
    const stepDeg = DOT_STEP / r * 180 / Math.PI;
    // 덜 찬 마지막 줄도 가운데 맞춘다. 한쪽으로 몰리면 부채가 기울어 보인다
    const first = centerDeg - stepDeg * (n - 1) / 2;
    for (let i = 0; i < n; i++) points.push({ r, deg: first + stepDeg * i });
    row++;
  }
  return points;
}

/** 부채꼴 바탕(두 호와 두 직선). 분야마다 점 무리를 한 덩어리로 보이게 한다 */
function sectorPath(rIn, rOut, fromDeg, toDeg) {
  const [x1, y1] = polar(rOut, fromDeg), [x2, y2] = polar(rOut, toDeg);
  const [x3, y3] = polar(rIn, toDeg), [x4, y4] = polar(rIn, fromDeg);
  const large = toDeg - fromDeg > 180 ? 1 : 0;
  return `M${f1(x1)} ${f1(y1)}A${rOut} ${rOut} 0 ${large} 1 ${f1(x2)} ${f1(y2)}` +
    `L${f1(x3)} ${f1(y3)}A${rIn} ${rIn} 0 ${large} 0 ${f1(x4)} ${f1(y4)}Z`;
}

function svgHtml(domains) {
  const n = domains.length;
  // 부채 바탕끼리 띄우려고 칸의 75%만 쓴다. 분야가 한둘이면 칸이 너무 넓어 70°로 막는다
  const halfSpan = Math.min(180 / n * 0.75, 35);
  let maxR = DOMAIN_RING + DOMAIN_R;
  const back = [], spokes = [], nodes = [], dots = [];

  domains.forEach((d, i) => {
    const deg = -90 + i * 360 / n;
    const pts = fanLayout(d.topics.length, deg, halfSpan);
    const outer = pts.length ? pts[pts.length - 1].r : FAN_START;
    maxR = Math.max(maxR, outer + DOT_R);
    const rIn = FAN_START - 16, rOut = outer + 16;
    const pad = 14 / rIn * 180 / Math.PI;
    back.push(`<path class="map-sector" d="${sectorPath(rIn, rOut, deg - halfSpan - pad, deg + halfSpan + pad)}"/>`);

    const [sx, sy] = polar(CENTER_R, deg), [ex, ey] = polar(DOMAIN_RING - DOMAIN_R, deg);
    const [fx, fy] = polar(DOMAIN_RING + DOMAIN_R, deg), [gx, gy] = polar(rIn, deg);
    spokes.push(`<line class="map-spoke" x1="${f1(sx)}" y1="${f1(sy)}" x2="${f1(ex)}" y2="${f1(ey)}"/>`,
      `<line class="map-spoke map-spoke--fan" x1="${f1(fx)}" y1="${f1(fy)}" x2="${f1(gx)}" y2="${f1(gy)}"/>`);

    const [cx, cy] = polar(DOMAIN_RING, deg);
    const label = d.label;
    const fs = label.length <= 4 ? 14 : label.length <= 6 ? 12.5 : 11;
    // 일곱 글자를 넘으면 원 안에 맞춰 글자 폭을 줄인다
    const fit = label.length > 7 ? ` textLength="${DOMAIN_R * 2 - 12}" lengthAdjust="spacingAndGlyphs"` : "";
    nodes.push(`<g class="map-domain-node">
      <circle cx="${f1(cx)}" cy="${f1(cy)}" r="${DOMAIN_R}"/>
      <text x="${f1(cx)}" y="${f1(cy - 3)}" class="map-domain-label" font-size="${fs}"${fit}>${escapeHtml(label)}</text>
      <text x="${f1(cx)}" y="${f1(cy + 14)}" class="map-domain-count">${escapeHtml(domainCount(d))}</text>
    </g>`);

    d.topics.forEach((t, k) => {
      const { state } = topicIndex.get(String(t.id));
      const look = lookOf(state);
      const [x, y] = polar(pts[k].r, pts[k].deg);
      dots.push(`<circle class="map-dot map-dot--${look}" cx="${f1(x)}" cy="${f1(y)}" r="${DOT_R}"
        data-id="${t.id}" tabindex="0" role="button"
        aria-label="${escapeHtml(`${t.title}, ${d.label}, ${STATE_LABEL[look]}`)}"></circle>`);
    });
  });

  const h = Math.ceil(maxR + 20);
  return `<svg class="map-svg" viewBox="${-h} ${-h} ${h * 2} ${h * 2}" role="group" aria-label="백엔드 기술 지도">
    ${back.join("")}${spokes.join("")}
    <g class="map-center"><circle r="${CENTER_R}"/><text y="5">백엔드</text></g>
    ${nodes.join("")}${dots.join("")}
  </svg>`;
}

/** 분야 원·목록 제목 옆 숫자. 로그인이면 다 맞힌 주제 수, 아니면 주제 수 */
function domainCount(d) {
  if (!loggedIn) return `주제 ${d.topics.length}`;
  const done = d.topics.filter(t => topicIndex.get(String(t.id)).state === "done").length;
  return `${done} / ${d.topics.length}`;
}

/* ── 좁은 화면: 분야별 접는 목록 ── */
function listHtml(domains) {
  return domains.map(d => `
    <details class="map-domain">
      <summary><span class="map-domain-name">${escapeHtml(d.label)}</span>
        <span class="meta">${escapeHtml(domainCount(d))}</span></summary>
      <ul>${d.topics.map(t => {
        const look = lookOf(topicIndex.get(String(t.id)).state);
        return `<li><button type="button" class="map-topic" data-id="${t.id}" aria-pressed="false">
          <span class="map-swatch map-dot--${look}" aria-hidden="true"></span>
          <span class="map-topic-name">${escapeHtml(t.title)}</span>
          <span class="visually-hidden">${STATE_LABEL[look]}</span></button></li>`;
      }).join("")}</ul>
    </details>`).join("");
}

/* ── 옆 칸(좁은 화면에선 목록 아래) ── */
function renderPanel(id) {
  const panel = document.getElementById("mapPanel");
  panel.hidden = false;
  const hit = id && topicIndex.get(id);
  if (!hit) {
    panel.innerHTML = `<p class="meta map-panel-hint">점을 누르면 주제 이름과 문서가 여기 떠요.</p>
      <p class="meta">분야 ${new Set([...topicIndex.values()].map(v => v.domain.code)).size}개 · 주제 ${topicIndex.size}개</p>`;
    return;
  }
  const { topic: t, domain: d, state } = hit;
  const look = lookOf(state);
  // 준비 중이면 아래 상태 줄(「준비 중」)만 남긴다. 숫자도 링크도 없다
  let body = "";
  if (state !== "none") {
    const num = t.problemTotal === 0 ? `<p class="meta">아직 문제가 없어요</p>`
      : loggedIn ? `<p class="map-panel-num"><span class="num">${t.solved}<span class="of"> / ${t.problemTotal}</span></span>
          <span class="meta">맞힌 문제</span></p>`
        : `<p class="meta">문제 ${t.problemTotal}개</p>`;
    // 입문편을 먼저 — 문서 화면의 차례와 같다
    const docs = [...t.documents].sort((a, b) => (a.edition === "ADVANCED") - (b.edition === "ADVANCED"));
    body = num + `<ul class="map-docs">${docs.map(doc => {
      const adv = doc.edition === "ADVANCED";
      return `<li><a href="/document.html?slug=${encodeURIComponent(doc.slug)}">
        <span class="badge edition${adv ? " advanced" : ""}">${adv ? "심화편" : "입문편"}</span>
        <span>${escapeHtml(doc.title)}</span></a></li>`;
    }).join("")}</ul>`;
  }
  panel.innerHTML = `
    <div class="map-panel-domain"><span class="list-row-domain">${escapeHtml(d.label)}</span></div>
    <h2 class="map-panel-title">${escapeHtml(t.title)}</h2>
    <div class="map-panel-state"><span class="map-swatch map-dot--${look}" aria-hidden="true"></span>${STATE_LABEL[look]}</div>
    ${body}`;
}

function select(id) {
  document.querySelectorAll(".map-dot.is-selected").forEach(el => el.classList.remove("is-selected"));
  document.querySelectorAll(".map-topic[aria-pressed='true']").forEach(el => el.setAttribute("aria-pressed", "false"));
  document.querySelector(`.map-dot[data-id="${id}"]`)?.classList.add("is-selected");
  document.querySelector(`.map-topic[data-id="${id}"]`)?.setAttribute("aria-pressed", "true");
  renderPanel(id);
  // 좁은 화면에서는 패널이 목록 아래라 화면 밖일 수 있다. nearest면 아래 탭바에 가린다
  if (window.matchMedia("(max-width: 767px)").matches) {
    document.getElementById("mapPanel").scrollIntoView({ block: "center", behavior: "smooth" });
  }
}

/* ── 이벤트 ── */
function wire() {
  const board = document.getElementById("mapBoard");
  const tip = document.getElementById("mapTip");

  board.addEventListener("click", e => {
    const dot = e.target.closest(".map-dot");
    if (dot) select(dot.dataset.id);
  });
  board.addEventListener("keydown", e => {
    const dot = e.target.closest(".map-dot");
    if (dot && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); select(dot.dataset.id); }
  });

  // 점마다 이름을 적으면 백 개가 겹친다. 이름은 가리킬 때만 말풍선으로 띄운다
  const showTip = dot => {
    const hit = topicIndex.get(dot.dataset.id);
    if (!hit) return;
    tip.textContent = hit.topic.title;
    tip.hidden = false;
    const b = board.getBoundingClientRect(), r = dot.getBoundingClientRect();
    const x = r.left + r.width / 2 - b.left;
    // 말풍선이 판 밖으로 나가지 않게 가로 위치를 판 안에 묶는다
    const half = tip.offsetWidth / 2;
    tip.style.left = `${Math.min(Math.max(x, half + 4), b.width - half - 4)}px`;
    tip.style.top = `${r.top - b.top - 8}px`;
  };
  const hideTip = () => { tip.hidden = true; };
  board.addEventListener("pointerover", e => { const d = e.target.closest(".map-dot"); if (d) showTip(d); });
  board.addEventListener("pointerout", e => { if (e.target.closest(".map-dot")) hideTip(); });
  board.addEventListener("focusin", e => { const d = e.target.closest(".map-dot"); if (d) showTip(d); });
  board.addEventListener("focusout", hideTip);

  document.getElementById("mapList").addEventListener("click", e => {
    const btn = e.target.closest(".map-topic");
    if (btn) select(btn.dataset.id);
  });
}
