/* =====================================================================
 * csquiz 퀴즈 플레이어 — "한 문제씩" 진행하는 공용 UI (quiz.html / review.html)
 * ---------------------------------------------------------------------
 * v1은 문제 전부를 세로로 나열하고 문제마다 [제출] 버튼이 있었다.
 * v2는 실제 퀴즈 앱처럼 바꾼다:
 *   - 한 화면에 문제 하나(집중), 상단에 진행 바 + 점수
 *   - 보기는 라디오가 아니라 큰 버튼(모바일에서도 누르기 쉬움)
 *   - 제출 즉시 그 자리에서 정답/오답을 색으로 표시 + 해설
 *   - 마지막 문제 후 점수판 + 틀린 문제 복기
 *   - 키보드 지원: 1~9 보기 선택, Enter 제출/다음
 *
 * 퀴즈와 복습이 같은 파일을 쓰는 이유: "문제를 풀고 채점받는" 경험은 완전히 같고
 * (제출 API도 POST /api/quiz/submit 하나 — docs/10), 다른 건 문제 목록의 출처와
 * 문항에 붙는 배지(복습은 stage 표시)뿐이다. 화면 로직을 두 벌 만들면 반드시 어긋난다.
 *
 * 채점은 항상 서버가 한다(정답이 브라우저에 없다 — docs/03). 그래서 문제당 1회
 * POST /api/quiz/submit을 보내고, 그 응답(correct/correctAnswer/explanation)으로 화면을 그린다.
 * ===================================================================== */

/**
 * 플레이어 시작. mountEl 안에 전체 UI를 그린다.
 *
 * @param mountEl  플레이어를 그릴 컨테이너 요소
 * @param problems 문제 배열 — 퀴즈 API(id) / 복습 API(problemId) 양쪽 모양 모두 허용
 * @param opts     {
 *   reviewMode: true면 문항에 복습 배지(stage)를 표시,
 *   onExit:     "새 퀴즈/목록으로" 버튼을 눌렀을 때 호출(설정 화면 복귀 등),
 *   exitLabel:  그 버튼의 라벨(기본 "새 퀴즈 만들기"),
 *   onFinish:   결과 화면에 도달했을 때 호출(복습 페이지가 현황 갱신에 사용)
 * }
 */
function startPlayer(mountEl, problems, opts = {}) {
  // 상태는 이 클로저 안에만 산다 — 페이지 전역을 오염시키지 않고,
  // "다시 풀기"로 startPlayer를 재호출하면 상태가 통째로 초기화된다.
  const state = {
    idx: 0,                  // 현재 문제 번호(0부터)
    score: 0,
    selected: null,          // 현재 문제에서 고른 답(제출 전)
    answered: false,         // 현재 문제 채점 완료 여부(Enter 키의 의미가 제출→다음으로 바뀜)
    submitting: false,       // 제출 API 진행 중 — 더블클릭/Enter 연타로 중복 제출 방지
    misses: [],              // 틀린 문제 기록(결과 화면 복기용) {q, my, ans, exp}
    finished: false,
    // 순서 배열 — 누른 순서대로 담기는 보기 id. 순서를 배열의 위치로만 표현해서
    // 하나를 빼면 뒤 번호가 저절로 당겨진다(toggleOrder 주석).
    order: [],
    // 짝짓기 — 이어진 쌍 [{left: 보기id, token: 오른쪽 토큰}]과 지금 고른 왼쪽.
    pairs: [],
    matchLeft: null,
  };

  // 퀴즈 API는 id, 복습 API는 problemId — 어느 쪽이 와도 동작하게 여기서 흡수한다.
  const pidOf = p => p.id ?? p.problemId;

  render();

  /* ── 키보드 지원 ──
   * 문서 전체에 리스너 하나만 건다. 이전 플레이어가 남긴 리스너가 겹치지 않게
   * mountEl에 참조를 저장해 두고, 재시작 시 기존 것을 떼어낸다. */
  if (mountEl._playerKeyHandler) {
    document.removeEventListener("keydown", mountEl._playerKeyHandler);
  }
  const keyHandler = e => {
    if (state.finished) return;
    if (e.target.tagName === "INPUT") {          // 단답형 입력 중에는 숫자 키를 가로채면 안 된다
      if (e.key === "Enter") { e.preventDefault(); primaryAction(); }
      return;
    }
    const p = problems[state.idx];
    if (!state.answered && /^[1-9]$/.test(e.key)) {
      // 1~9 = 보기 선택 (OX는 1=O, 2=X)
      const options = p.type === "OX" ? ["O", "X"] : (p.choices || []).map(c => String(c.id));
      const i = Number(e.key) - 1;
      if (i >= options.length) return;
      // 순서 배열은 "고르기"가 아니라 "순서대로 쌓기"다 — 같은 숫자키가 다른 일을 한다.
      // 짝짓기는 두 열이라 숫자 하나로 무엇을 가리키는지 정할 수 없어 단축키를 두지 않는다.
      if (p.type === "ORDERING") toggleOrder(options[i]);
      else if (p.type !== "MATCHING") selectOption(options[i]);
    } else if (e.key === "Enter") {
      primaryAction();
    }
  };
  document.addEventListener("keydown", keyHandler);
  mountEl._playerKeyHandler = keyHandler;

  /** Enter가 하는 일 — 채점 전이면 제출, 채점 후면 다음 문제(마지막이면 결과). */
  function primaryAction() {
    if (state.answered) next();
    else submit();
  }

  /* ── 화면 그리기 ── */

  function render() {
    const p = problems[state.idx];
    /* 진행 숫자는 <이번에 푸는 묶음>이 아니라 부르는 쪽이 정한 기준으로 센다 — 2026-09-08.
     *
     * 오늘의 퀴즈는 <안 푼 문제만> 플레이어에 넘긴다(daily.html). 그래서 열 문제 중 하나를
     * 이미 푼 사람이 들어오면 "1 / 9"가 떴다. 홈은 바로 앞 화면에서 "9문제 남았어요"라고
     * 하고, 오늘의 퀴즈 카드도 "1 / 10"이라고 하는데, 정작 푸는 화면만 분모가 달랐다.
     * 세 화면이 같은 세트를 두고 서로 다른 숫자를 말하면 어느 것도 못 믿게 된다.
     *
     * opts.offset은 <이미 푼 개수>, opts.total은 <세트 전체>다. 안 주면 지금까지처럼
     * 넘겨받은 배열 기준이다 — 자유 퀴즈와 복습은 그 배열이 곧 전부라 바꿀 것이 없다. */
    const offset = opts.offset || 0;
    const total = opts.total || problems.length;
    const progressPct = ((state.idx + offset) / total) * 100;

    // 복습 모드면 지금 사다리 몇 번째 칸인지 보여준다 — "이 문제를 몇 번째 다시 보는지" 맥락 제공
    const reviewBadge = opts.reviewMode && p.stage !== undefined
      ? `<span class="badge orange">복습 ${p.stage + 1}단계</span>` : "";
    // 문제에 badgeText가 실려 있으면 그대로 배지로 — 오늘의 퀴즈가 "왜 이 문제가 나왔는지"
    // (복습/취약 보강/새 문제)를 표시하는 데 쓴다. 페이지가 데이터에 라벨을 실어 보내는
    // 방식이라, 새 모드가 생겨도 플레이어에 모드 분기가 늘지 않는다.
    const extraBadge = p.badgeText
      ? `<span class="badge orange">${escapeHtml(p.badgeText)}</span>` : "";

    /* 얇은 띠 + 메타 줄 + 카드. 2026-09-06 개편에서 이 셋으로 갈랐다.
     *
     * [배지가 카드 밖으로 나왔다] 예전에는 문제 카드 안 맨 위에 있었다. 밖으로 내면 카드는
     * 지문과 보기만 담게 되고, "이 문제가 무엇인가"는 카드 위에서 한 번에 읽힌다.
     * 폰에서는 이 줄이 상단 바 아래에 달라붙어, 지문이 길어도 몇 번째인지가 안 사라진다.
     *
     * [맞힌 수를 뺐다] 푸는 중에 점수를 보여 주면 남은 문제를 푸는 태도가 바뀐다 —
     * "이미 셋 틀렸으니 대충"이 되기 쉽다. 점수는 다 끝난 뒤 결과 화면에서 한 번 본다.
     * state.score는 그 결과 화면이 계속 쓴다. */
    mountEl.innerHTML = `
      <div class="play-sticky">
        <div class="play-rail" role="progressbar" aria-label="퀴즈 진행률"
             aria-valuenow="${state.idx + offset}" aria-valuemin="0" aria-valuemax="${total}"
          ><i style="width:${progressPct}%"></i></div>
        <div class="play-head">
          <span class="badge">${escapeHtml(domainLabel(p.domain))}</span>
          ${difficultyBadge(p.difficulty)}
          <span class="badge gray">${escapeHtml(typeLabel(p.type))}</span>
          ${reviewBadge}${extraBadge}
          <span class="count">${state.idx + 1 + offset} / ${total}</span>
        </div>
      </div>

      <div class="card player-card fade-in">
        <div class="q-text">${escapeHtml(p.question)}</div>
        <div id="optArea">${renderInput(p)}</div>
        <div id="feedback"></div>
        <div class="player-actions">
          <button id="submitBtn" disabled>제출</button>
        </div>
        ${keyHint(`<kbd>1</kbd>~<kbd>9</kbd> 보기 선택 · <kbd>Enter</kbd> 제출/다음`)}
      </div>`;

    // 보기 버튼/입력에 이벤트 연결 (innerHTML로 그린 뒤라 여기서 바인딩)
    if (p.type === "SHORT_ANSWER") {
      const input = mountEl.querySelector("#shortInput");
      input.focus();
      // 입력이 생기면 제출 버튼 활성화 — "빈 답 제출"을 버튼 단계에서 차단
      input.addEventListener("input", () => {
        state.selected = input.value.trim();
        mountEl.querySelector("#submitBtn").disabled = !state.selected;
      });
    } else {
      bindOptions(p);
    }
    mountEl.querySelector("#submitBtn").addEventListener("click", submit);
  }

  /**
   * 숫자키 안내 줄 — 설정에서 끄면 안 그린다.
   *
   * <p><b>여기만 끌 수 있다.</b> 순서 배열의 "순서대로 누르세요"나 짝짓기의 "왼쪽을 누른 뒤
   * 오른쪽을"은 같은 {@code .kbd-hint} 모양이지만 성격이 다르다 — 그건 <b>조작 방법</b>이라
   * 없으면 무엇을 해야 하는지 알 수 없다. 이 줄은 마우스로도 다 되는 일에 대한
   * <b>단축키 안내</b>라, 아는 사람에게는 매 문제 반복되는 소음이다.
   *
   * <p>번호 칩(.opt .key)은 이 설정과 무관하게 남는다. 칩은 힌트가 아니라 <b>보기의 이름</b>이라,
   * 키보드를 안 써도 "3번 보기"라고 말할 수 있어야 한다.
   */
  function keyHint(html) {
    return getPref("csquiz_keyhint") === "off" ? "" : `<div class="kbd-hint">${html}</div>`;
  }

  /**
   * 문제 유형별 입력 UI — 객관식·OX는 버튼, 단답형은 텍스트 입력,
   * 순서 배열은 누른 순서대로 번호 매기기, 짝짓기는 두 열을 잇기.
   *
   * 순서 배열에 드래그를 쓰지 않은 이유: 모바일에서 드래그는 스크롤과 싸운다.
   * "순서대로 누른다"는 손가락으로도 정확하고, 키보드 1~9 단축키와도 그대로 이어진다.
   */
  function renderInput(p) {
    /* [보기에 aria-pressed를 다는 이유 — 2026-09-07]
     *
     * 보기는 <button>인데 role도 상태도 없었다. 스크린리더는 "버튼"이라고만 읽어서,
     * 넷 중 하나를 고르는 자리라는 것도, 지금 무엇을 골랐는지도 전달되지 않았다.
     * 화면에는 남보라 테두리가 생기지만 그건 <눈으로만> 보이는 신호다.
     *
     * role="radio"로 가지 않은 이유: 라디오 그룹은 <화살표로 옮기고 탭은 그룹을 통째로
     * 건너뛴다>는 약속이 붙어 있다. 이 화면의 키보드 규약은 이미 다르다 — 숫자키로
     * 고르고 탭으로 보기를 하나씩 지난다. 역할만 라디오로 바꾸면 스크린리더 사용자가
     * 화살표를 눌렀는데 아무 일도 안 일어나는, <말과 행동이 다른> 상태가 된다.
     * 지금 있는 규약을 정확히 설명하는 쪽을 골랐다: 누를 수 있는 것들이고(button),
     * 그중 눌린 것이 있다(aria-pressed).
     *
     * 묶음에는 이름을 준다. 그래야 "보기, 4개 중 1번" 같은 안내가 나온다. */
    if (p.type === "MULTIPLE_CHOICE") {
      const opts = p.choices.map(c => `
        <button class="opt" data-value="${c.id}" aria-pressed="false">
          <span class="key">${c.seq}</span><span>${escapeHtml(c.text)}</span>
        </button>`).join("");
      return `<div role="group" aria-label="보기 ${p.choices.length}개">${opts}</div>`;
    }
    if (p.type === "OX") {
      return `<div class="ox-row" role="group" aria-label="보기 2개">
        <button class="opt" data-value="O" aria-pressed="false"><span>⭕ O</span></button>
        <button class="opt" data-value="X" aria-pressed="false"><span>❌ X</span></button>
      </div>`;
    }
    if (p.type === "ORDERING") return renderOrdering(p);
    if (p.type === "MATCHING") return renderMatching(p);
    // SHORT_ANSWER — Enter 제출은 전역 키 핸들러가 처리
    return `<input type="text" id="shortInput" placeholder="답을 입력하세요" autocomplete="off">`;
  }

  /** 순서 배열 — 고른 항목에는 누른 순서를, 안 고른 항목에는 점을 찍는다. */
  function renderOrdering(p) {
    const rows = p.choices.map(c => {
      const at = state.order.indexOf(String(c.id));
      const picked = at >= 0;
      return `<button class="opt${picked ? " selected" : ""}" data-order="${c.id}"
        aria-pressed="${picked}">
        <span class="key">${picked ? at + 1 : "·"}</span><span>${escapeHtml(c.text)}</span>
      </button>`;
    }).join("");
    // 고른 것에는 몇 번째인지가 .key에 들어가 이름의 일부로 읽힌다("3 파일을 연다").
    return `<div role="group" aria-label="보기 ${p.choices.length}개, 순서대로 고르기">${rows}</div>
      <div class="kbd-hint">순서대로 누르세요. 다시 누르면 취소됩니다.</div>`;
  }

  /**
   * 짝짓기 — 왼쪽을 누르고 오른쪽을 누르면 한 쌍. 이어진 둘에는 같은 번호가 붙는다.
   *
   * 선을 그리지 않고 번호로 짝을 보여 주는 이유: 선을 그리려면 두 열의 화면 좌표를 재야 하고,
   * 창 크기가 바뀌거나 글이 줄바꿈될 때마다 다시 그려야 한다. 번호는 그 전부를 안 해도 된다.
   */
  function renderMatching(p) {
    const pairIndexOfLeft = id => state.pairs.findIndex(x => x.left === String(id));
    const pairIndexOfToken = t => state.pairs.findIndex(x => x.token === t);

    const left = p.choices.map(c => {
      const at = pairIndexOfLeft(c.id);
      const active = state.matchLeft === String(c.id);
      return `<button class="opt${at >= 0 ? " selected" : ""}${active ? " active" : ""}"
        data-left="${c.id}" aria-pressed="${at >= 0}">
        <span class="key">${at >= 0 ? at + 1 : "·"}</span><span>${escapeHtml(c.text)}</span>
      </button>`;
    }).join("");

    const right = (p.matchOptions || []).map(o => {
      const at = pairIndexOfToken(o.token);
      return `<button class="opt${at >= 0 ? " selected" : ""}" data-token="${escapeHtml(o.token)}"
        aria-pressed="${at >= 0}">
        <span class="key">${at >= 0 ? at + 1 : "·"}</span><span>${escapeHtml(o.text)}</span>
      </button>`;
    }).join("");

    return `<div class="match-grid">
        <div class="match-col" role="group" aria-label="왼쪽 항목">${left}</div>
        <div class="match-col" role="group" aria-label="오른쪽 항목">${right}</div>
      </div>
      <div class="kbd-hint">왼쪽을 누른 뒤 오른쪽을 누르면 이어집니다. 이어진 것을 누르면 풀립니다.</div>`;
  }

  /** 보기 선택(채점 전) — 선택 표시를 바꾸고 제출 버튼을 활성화한다. */
  function selectOption(value) {
    if (state.answered || state.submitting) return;
    state.selected = value;
    mountEl.querySelectorAll(".opt").forEach(btn => {
      const 눌림 = btn.dataset.value === value;
      btn.classList.toggle("selected", 눌림);
      // 클래스만 바꾸면 <보이는 상태>만 바뀐다. 소리로도 바뀌어야 하므로 함께 적는다.
      btn.setAttribute("aria-pressed", String(눌림));
    });
    mountEl.querySelector("#submitBtn").disabled = false;
  }

  /**
   * 순서 배열에서 항목 하나를 눌렀을 때 — 이미 고른 것이면 빼고, 아니면 뒤에 붙인다.
   *
   * 뺄 때 뒤 번호를 다시 매기지 않아도 되는 이유: 순서는 배열의 <위치>로만 표현하므로
   * 하나를 빼면 나머지가 저절로 당겨진다. 번호를 따로 들고 있었다면 여기서 재계산이 필요했다.
   */
  function toggleOrder(id) {
    if (state.answered || state.submitting) return;
    const at = state.order.indexOf(id);
    if (at >= 0) state.order.splice(at, 1);
    else state.order.push(id);
    // 전부 배열했을 때만 제출할 수 있다 — 서버도 개수가 다르면 400으로 막는다(gradeOrdering).
    const p = problems[state.idx];
    state.selected = state.order.length === p.choices.length ? state.order.join("|") : "";
    refreshInput();
  }

  /** 짝짓기에서 왼쪽을 눌렀을 때 — 이미 이어져 있으면 그 쌍을 풀고, 아니면 "고른 왼쪽"이 된다. */
  function pickLeft(id) {
    if (state.answered || state.submitting) return;
    const at = state.pairs.findIndex(x => x.left === id);
    if (at >= 0) {
      state.pairs.splice(at, 1);
      state.matchLeft = null;
    } else {
      // 같은 것을 두 번 누르면 고르기를 취소한다 — 잘못 눌렀을 때 빠져나갈 길을 둔다.
      state.matchLeft = state.matchLeft === id ? null : id;
    }
    syncMatchAnswer();
  }

  /** 짝짓기에서 오른쪽을 눌렀을 때 — 왼쪽을 고른 상태에서만 쌍이 된다. */
  function pickRight(token) {
    if (state.answered || state.submitting) return;
    const at = state.pairs.findIndex(x => x.token === token);
    if (at >= 0) {
      state.pairs.splice(at, 1);   // 이어진 것을 풀기
    } else if (state.matchLeft) {
      state.pairs.push({ left: state.matchLeft, token });
    }
    state.matchLeft = null;
    syncMatchAnswer();
  }

  /** 만들어진 쌍을 제출 문자열로 — 전부 이었을 때만 제출 버튼이 열린다. */
  function syncMatchAnswer() {
    const p = problems[state.idx];
    state.selected = state.pairs.length === p.choices.length
      ? state.pairs.map(x => `${x.left}-${x.token}`).join("|")
      : "";
    refreshInput();
  }

  /**
   * 입력 영역만 다시 그린다 — 카드 전체를 다시 그리면 채점 결과 영역이 지워진다.
   * 이벤트는 innerHTML로 새로 그린 요소에 다시 걸어 준다.
   */
  function refreshInput() {
    const p = problems[state.idx];
    mountEl.querySelector("#optArea").innerHTML = renderInput(p);
    bindOptions(p);
    mountEl.querySelector("#submitBtn").disabled = !state.selected;
  }

  /** 입력 영역의 버튼에 유형별 클릭 동작을 건다(그린 직후마다 호출). */
  function bindOptions(p) {
    mountEl.querySelectorAll("[data-order]").forEach(btn => {
      btn.addEventListener("click", () => toggleOrder(btn.dataset.order));
    });
    mountEl.querySelectorAll("[data-left]").forEach(btn => {
      btn.addEventListener("click", () => pickLeft(btn.dataset.left));
    });
    mountEl.querySelectorAll("[data-token]").forEach(btn => {
      btn.addEventListener("click", () => pickRight(btn.dataset.token));
    });
    mountEl.querySelectorAll("[data-value]").forEach(btn => {
      btn.addEventListener("click", () => selectOption(btn.dataset.value));
    });
  }

  /* ── 제출/채점 ── */

  async function submit() {
    if (state.answered || state.submitting || !state.selected) return;
    const p = problems[state.idx];
    const feedbackEl = mountEl.querySelector("#feedback");

    if (!isLoggedIn()) {
      feedbackEl.innerHTML =
        `<div class="alert error">채점하려면 <a href="/login.html">로그인</a>이 필요합니다.</div>`;
      return;
    }

    state.submitting = true;
    const submitBtn = mountEl.querySelector("#submitBtn");
    submitBtn.disabled = true;

    try {
      const r = await api("/api/quiz/submit", {
        method: "POST",
        body: JSON.stringify({ problemId: pidOf(p), userAnswer: state.selected }),
      });
      state.answered = true;
      if (r.correct) state.score++;
      else state.misses.push({
        q: p.question,
        my: displayAnswer(p, state.selected),
        // 내가 고른 그 보기가 왜 틀렸는지(V15). 결과 화면 복기는 보기를 다시 그리지 않으므로
        // 오답 분석 전체가 아니라 <내가 고른 것> 하나만 가져온다 — 오답노트와 같은 판단이다.
        why: myRationale(r, state.selected),
        ans: r.correctAnswer ?? "",
        exp: r.explanation ?? "",
        doc: r.documentSlug ?? null,   // 결과 화면 복기에서도 개념 문서로 갈 수 있게(docs/15)
      });
      showFeedback(p, r);
    } catch (e) {
      // 실패하면 다시 제출할 수 있게 잠금을 되돌린다(answered로 만들지 않음)
      const text = e.status === 401
        ? `로그인이 만료됐습니다. <a href="/login.html">다시 로그인</a> 후 제출해 주세요.`
        : escapeHtml(e.message);
      feedbackEl.innerHTML = `<div class="alert error">${text}</div>`;
      submitBtn.disabled = false;
    } finally {
      state.submitting = false;
    }
  }

  /**
   * 내가 낸 답을 사람이 읽는 표기로 — 결과 화면 복기용.
   *
   * 서버도 오답노트에서 같은 일을 하지만(AnswerDisplay), 이 화면은 채점 응답만 들고 있고
   * 거기에는 <내 답>이 글자로 들어 있지 않다. 보기 목록은 이미 손에 있으므로 여기서 바꾼다.
   */
  function displayAnswer(p, value) {
    if (p.type === "MULTIPLE_CHOICE") {
      const c = (p.choices || []).find(c => String(c.id) === String(value));
      return c ? c.text : value;
    }
    if (p.type === "ORDERING") {
      const textOf = id => (p.choices || []).find(c => String(c.id) === id)?.text ?? id;
      return String(value).split("|").map(textOf).join(" → ");
    }
    if (p.type === "MATCHING") {
      const leftOf = id => (p.choices || []).find(c => String(c.id) === id)?.text ?? id;
      const rightOf = t => (p.matchOptions || []).find(o => o.token === t)?.text ?? t;
      return String(value).split("|").map(entry => {
        const at = entry.indexOf("-");
        return at < 0 ? entry : `${leftOf(entry.slice(0, at))} → ${rightOf(entry.slice(at + 1))}`;
      }).join("\n");
    }
    return value;
  }

  /* 근거 개념 문서 링크 — 채점 결과 안에만 붙는다(docs/15 3단계).
   *
   * 이 문서는 이 문제의 <출제 근거>다. 풀기 전에 보여주면 답을 알려주는 꼴이라,
   * 해설과 똑같이 "제출이라는 대가를 치른 뒤에만" 나타나야 한다.
   * 서버가 실재하는 문서일 때만 slug를 내려주므로(QuizSubmitResponse) 여기서는 존재 여부를
   * 다시 따지지 않는다 — 규칙이 두 곳에 있으면 언젠가 어긋난다.
   *
   * 새 탭(target=_blank)으로 여는 이유: 퀴즈는 여러 문제를 이어서 푸는 흐름이라, 같은 탭에서
   * 문서로 이동하면 풀던 세트가 통째로 날아간다(점수·진행 상태가 화면 메모리에만 있다). */
  /* 한 줄로 붙여 쓰는 것이 <중요하다>. 이 줄은 .explain 안에 있고 그 클래스에는
   * white-space: pre-wrap이 걸려 있다(해설의 줄바꿈을 살리려고 넣은 규칙이다).
   * 그래서 템플릿 문자열을 예쁘게 들여쓰면 그 <들여쓰기와 줄바꿈이 화면에 그대로> 나온다 —
   * 실제로 📖만 위에 뜨고 글자가 다음 줄로 내려가 있었다(2026-09-08에 발견).
   * 코드 모양을 위해 넣은 공백이 화면에 새어 나오는 자리라, 여기서는 줄을 접지 않는다. */
  function docLink(r) {
    if (!r.documentSlug) return "";
    const href = `/document.html?slug=${encodeURIComponent(r.documentSlug)}`;
    return `<div class="explain doc-link">`
      + `📖 <a href="${href}" target="_blank" rel="noopener">이 문제의 개념 문서 읽기</a>`
      + `</div>`;
  }

  /* 오답 분석 — 오답 보기마다 왜 틀렸는지를 <이 화면의 번호>와 함께 늘어놓는다(V15).
   *
   * [여기가 번호 문제를 푸는 자리다]
   * 보기는 요청마다 다시 섞여 나오므로(QuizChoiceItem.shuffledFrom) 서버는 학습자 화면의
   * 번호를 알지 못한다. 그래서 해설이 "②번"이라고 적는 것을 저장 단계에서 막아 왔다.
   * 그런데 번호를 아는 쪽은 여기다 — 방금 p.choices의 seq로 배지를 찍었다.
   * 서버는 id로만 말하고(QuizChoiceResult) 번호는 화면이 붙인다. 그러면 아무리 섞여도 맞는다.
   *
   * [옛 문제는 그리지 않는다]
   * 이 컬럼이 생기기 전에 승인된 문제는 오답 설명이 통짜 해설 안에 녹아 있어 rationale이
   * 전부 비어 있다. 그때 빈 제목만 뜨면 "설명이 빠졌나?" 싶은 화면이 되므로 절 자체를 접는다.
   * 두 형식이 공존한다는 판단이 화면까지 이어지는 자리다(V15 주석). */
  /** 내가 고른 보기의 오답 설명 — 없으면 빈 문자열(객관식이 아니거나 옛 문제). */
  function myRationale(r, selected) {
    const mine = (r.choices || []).find(c => String(c.id) === String(selected));
    return mine && !mine.correct ? (mine.rationale ?? "") : "";
  }

  function wrongAnalysis(p, r) {
    if (p.type !== "MULTIPLE_CHOICE") return "";

    // 서버가 준 보기별 결과를 id로 찾을 수 있게 만들어 둔다. String으로 맞추는 이유는
    // data-value가 문자열이고 JSON의 id는 숫자라, === 로 비교하면 영영 안 맞기 때문이다.
    const byId = new Map((r.choices || []).map(c => [String(c.id), c]));

    // 화면에 그린 순서(seq) 그대로 훑는다 — 읽는 사람이 위에서 본 번호와 같은 차례여야 한다.
    const rows = (p.choices || [])
      .map(c => ({ seq: c.seq, result: byId.get(String(c.id)) }))
      .filter(x => x.result && !x.result.correct && x.result.rationale)
      .map(x => `<li><b>${x.seq}번</b> — ${escapeHtml(x.result.rationale)}</li>`);

    if (rows.length === 0) return "";
    return `<div class="explain wrong-analysis">
      <div class="wrong-analysis-title">오답 분석</div>
      <ul>${rows.join("")}</ul>
    </div>`;
  }

  /** 채점 결과를 보기 색 + 피드백 박스로 표시하고, [다음] 버튼으로 바꾼다. */
  function showFeedback(p, r) {
    // 1) 보기 자체에 색: 정답 보기는 초록, 내가 고른 오답은 빨강.
    //    서버는 정답을 "표시용 텍스트(correctAnswer)"로만 주므로(정답 id는 안 내려줌 — 스펙),
    //    객관식은 보기 text와 문자열 비교로 정답 보기를 찾는다.
    //    순서 배열·짝짓기는 <보기 하나>에 정오가 붙지 않는다 — 맞고 틀림이 배열 전체,
    //    연결 전체의 성질이라 어느 버튼을 초록으로 칠할지 정할 수 없다. 그래서 색은 건드리지
    //    않고 누른 흔적(번호)만 남긴 채 잠근다. 정답은 아래 피드백 상자가 통째로 보여 준다.
    const wholeAnswer = p.type === "ORDERING" || p.type === "MATCHING";
    mountEl.querySelectorAll(".opt").forEach(btn => {
      btn.disabled = true;
      if (wholeAnswer) return;
      const val = btn.dataset.value;
      const isMine = String(val) === String(state.selected);
      let isAnswer;
      if (p.type === "OX") {
        isAnswer = val.toUpperCase() === String(r.correctAnswer).toUpperCase();
      } else {
        const c = (p.choices || []).find(c => String(c.id) === val);
        isAnswer = c && c.text === r.correctAnswer;
      }
      // 색 <그리고> 글자. 초록과 빨강이 같은 회색으로 보이는 사람에게 테두리 색은
      // 아무 정보도 아니라, 어느 보기가 답이었는지 알 방법이 없어진다.
      // "내 답"은 틀렸을 때만 붙인다 — 맞았으면 정답 보기가 곧 내 답이라 두 번 말할 필요가 없다.
      if (isAnswer) {
        btn.classList.add("is-correct");
        btn.insertAdjacentHTML("beforeend", `<span class="mark">✓ 정답</span>`);
      } else if (isMine && !r.correct) {
        btn.classList.add("is-wrong");
        btn.insertAdjacentHTML("beforeend", `<span class="mark">✕ 내 답</span>`);
      }
      btn.classList.remove("selected");
    });
    const shortInput = mountEl.querySelector("#shortInput");
    if (shortInput) shortInput.disabled = true;

    // 2) 피드백 박스: 판정 + 정답 + 해설 + 오답 분석
    mountEl.querySelector("#feedback").innerHTML = `
      <div class="feedback ${r.correct ? "correct" : "wrong"} fade-in">
        <span class="verdict">${r.correct ? "🎉 정답입니다!" : "😅 아쉬워요, 오답입니다"}</span>
        ${r.correct ? "" : `<div class="answer-line">정답: <b>${escapeHtml(r.correctAnswer ?? "")}</b></div>`}
        ${r.explanation ? `<div class="explain">${escapeHtml(r.explanation)}</div>` : ""}
        ${wrongAnalysis(p, r)}
        ${docLink(r)}
        ${opts.reviewMode ? `<div class="explain" style="font-size:.82rem">${
          r.correct ? "복습 간격이 한 단계 늘어났어요. 다음엔 더 나중에 만나요 👋"
                    : "내일 다시 만나요. 오늘 틀린 건 내일이 복습 타이밍이에요 📅"}</div>` : ""}
        ${reportBlock(p.id)}
      </div>`;

    // 3) 제출 버튼 → 다음/결과 버튼으로 교체
    const isLast = state.idx === problems.length - 1;
    const actions = mountEl.querySelector(".player-actions");
    actions.innerHTML = `<button id="nextBtn" class="btn-lg">${isLast ? "결과 보기 🏁" : "다음 문제 →"}</button>`;
    actions.querySelector("#nextBtn").addEventListener("click", next);
    actions.querySelector("#nextBtn").focus();   // Enter로 바로 넘어갈 수 있게 포커스 이동
  }

  /* ── 진행/종료 ── */

  function next() {
    if (!state.answered) return;
    if (state.idx === problems.length - 1) { finish(); return; }
    state.idx++;
    state.selected = null;
    state.answered = false;
    // 순서·짝짓기 상태도 같이 비운다 — 안 비우면 다음 문제의 보기에 앞 문제의 번호가 붙는다.
    state.order = [];
    state.pairs = [];
    state.matchLeft = null;
    render();
    window.scrollTo({ top: 0 });   // 긴 해설을 읽고 내려간 스크롤을 문제 위치로 되돌린다
  }

  function finish() {
    state.finished = true;
    document.removeEventListener("keydown", keyHandler);

    const total = problems.length;
    const pct = Math.round((state.score / total) * 100);
    // 점수대별 한 줄 코멘트 — 숫자만 던지는 것보다 "다음 행동"을 제안하는 쪽이 학습 사이트답다
    const msg = pct === 100 ? "완벽해요! 🏆"
      : pct >= 80 ? "훌륭해요! 조금만 더 다듬으면 완벽 👏"
      : pct >= 50 ? "좋아요, 틀린 문제만 복습하면 금방 올라요 💪"
      : "괜찮아요, 지금 틀린 게 실전에서 안 틀리는 길이에요 🌱";

    const missHtml = state.misses.length === 0 ? "" : `
      <div class="review-list">
        <h2>틀린 문제 복기 (${state.misses.length}개)</h2>
        ${state.misses.map(m => `
          <div class="card miss-item">
            <div class="q">${escapeHtml(m.q)}</div>
            <div class="my">내 답: ${escapeHtml(m.my)}</div>
            <div class="ans">정답: ${escapeHtml(m.ans)}</div>
            ${m.why ? `<div class="my" style="margin-top:4px">왜 틀렸나: ${escapeHtml(m.why)}</div>` : ""}
            ${m.exp ? `<div class="exp">${escapeHtml(m.exp)}</div>` : ""}
            ${m.doc ? `<div class="exp" style="font-size:.86rem">📖 <a href="/document.html?slug=${
              encodeURIComponent(m.doc)}" target="_blank" rel="noopener">이 문제의 개념 문서 읽기</a></div>` : ""}
          </div>`).join("")}
        <p class="meta">틀린 문제는 <a href="/review.html">복습</a> 사다리에 자동으로 올라갔어요 —
          내일 "오늘의 복습"에서 다시 만나요.</p>
      </div>`;

    mountEl.innerHTML = `
      <div class="card score-board fade-in">
        <div class="big">${state.score}<small> / ${total}</small></div>
        <div class="msg">${msg}</div>
        <div class="sub">정답률 ${pct}%</div>
        <div class="actions">
          <button id="retryBtn" class="btn-outline">같은 문제 다시 풀기</button>
          <button id="exitBtn">${escapeHtml(opts.exitLabel || "새 퀴즈 만들기")}</button>
        </div>
      </div>
      ${missHtml}`;

    // 다시 풀기 = 같은 문제로 플레이어 재시작(상태가 클로저라 통째로 초기화된다).
    // 단, 채점 이력(Submission)은 서버에 또 쌓인다 — 재도전도 학습 이력이므로 의도된 동작.
    mountEl.querySelector("#retryBtn").addEventListener("click", () => startPlayer(mountEl, problems, opts));
    mountEl.querySelector("#exitBtn").addEventListener("click", () => {
      if (opts.onExit) opts.onExit();
    });

    loadReviewBadge();             // 방금 틀린 문제로 복습 개수가 바뀌었을 수 있다 — 배지 갱신
    if (opts.onFinish) opts.onFinish(state.score, total, state.misses);
    window.scrollTo({ top: 0 });
  }
}
