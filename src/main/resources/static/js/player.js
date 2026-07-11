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
      if (i < options.length) selectOption(options[i]);
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
    const total = problems.length;
    const progressPct = (state.idx / total) * 100;

    // 복습 모드면 지금 사다리 몇 번째 칸인지 보여준다 — "이 문제를 몇 번째 다시 보는지" 맥락 제공
    const reviewBadge = opts.reviewMode && p.stage !== undefined
      ? `<span class="badge orange">복습 ${p.stage + 1}단계</span>` : "";

    mountEl.innerHTML = `
      <div class="player-top">
        <span class="count">문제 ${state.idx + 1} / ${total}</span>
        <div class="progress"><div style="width:${progressPct}%"></div></div>
        <span class="score">맞힌 수 ${state.score}</span>
      </div>

      <div class="card player-card fade-in">
        <div>
          <span class="badge">${escapeHtml(domainLabel(p.domain))}</span>
          <span class="badge gray">${escapeHtml(difficultyLabel(p.difficulty))}</span>
          <span class="badge gray">${escapeHtml(typeLabel(p.type))}</span>
          ${reviewBadge}
        </div>
        <div class="q-text">${escapeHtml(p.question)}</div>
        <div id="optArea">${renderInput(p)}</div>
        <div id="feedback"></div>
        <div class="player-actions">
          <button id="submitBtn" disabled>제출</button>
        </div>
        <div class="kbd-hint"><kbd>1</kbd>~<kbd>9</kbd> 보기 선택 · <kbd>Enter</kbd> 제출/다음</div>
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
      mountEl.querySelectorAll(".opt").forEach(btn => {
        btn.addEventListener("click", () => selectOption(btn.dataset.value));
      });
    }
    mountEl.querySelector("#submitBtn").addEventListener("click", submit);
  }

  /** 문제 유형별 입력 UI — 객관식·OX는 버튼, 단답형은 텍스트 입력. */
  function renderInput(p) {
    if (p.type === "MULTIPLE_CHOICE") {
      return p.choices.map(c => `
        <button class="opt" data-value="${c.id}">
          <span class="key">${c.seq}</span><span>${escapeHtml(c.text)}</span>
        </button>`).join("");
    }
    if (p.type === "OX") {
      return `<div class="ox-row">
        <button class="opt" data-value="O"><span>⭕ O</span></button>
        <button class="opt" data-value="X"><span>❌ X</span></button>
      </div>`;
    }
    // SHORT_ANSWER — Enter 제출은 전역 키 핸들러가 처리
    return `<input type="text" id="shortInput" placeholder="답을 입력하세요" autocomplete="off">`;
  }

  /** 보기 선택(채점 전) — 선택 표시를 바꾸고 제출 버튼을 활성화한다. */
  function selectOption(value) {
    if (state.answered || state.submitting) return;
    state.selected = value;
    mountEl.querySelectorAll(".opt").forEach(btn => {
      btn.classList.toggle("selected", btn.dataset.value === value);
    });
    mountEl.querySelector("#submitBtn").disabled = false;
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
        ans: r.correctAnswer ?? "",
        exp: r.explanation ?? "",
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

  /** 내가 낸 답을 사람이 읽는 표기로 — 객관식은 choiceId가 아니라 보기 글로(결과 복기용). */
  function displayAnswer(p, value) {
    if (p.type !== "MULTIPLE_CHOICE") return value;
    const c = (p.choices || []).find(c => String(c.id) === String(value));
    return c ? c.text : value;
  }

  /** 채점 결과를 보기 색 + 피드백 박스로 표시하고, [다음] 버튼으로 바꾼다. */
  function showFeedback(p, r) {
    // 1) 보기 자체에 색: 정답 보기는 초록, 내가 고른 오답은 빨강.
    //    서버는 정답을 "표시용 텍스트(correctAnswer)"로만 주므로(정답 id는 안 내려줌 — 스펙),
    //    객관식은 보기 text와 문자열 비교로 정답 보기를 찾는다.
    mountEl.querySelectorAll(".opt").forEach(btn => {
      btn.disabled = true;
      const val = btn.dataset.value;
      const isMine = String(val) === String(state.selected);
      let isAnswer;
      if (p.type === "OX") {
        isAnswer = val.toUpperCase() === String(r.correctAnswer).toUpperCase();
      } else {
        const c = (p.choices || []).find(c => String(c.id) === val);
        isAnswer = c && c.text === r.correctAnswer;
      }
      if (isAnswer) btn.classList.add("is-correct");
      else if (isMine && !r.correct) btn.classList.add("is-wrong");
      btn.classList.remove("selected");
    });
    const shortInput = mountEl.querySelector("#shortInput");
    if (shortInput) shortInput.disabled = true;

    // 2) 피드백 박스: 판정 + 정답 + 해설
    mountEl.querySelector("#feedback").innerHTML = `
      <div class="feedback ${r.correct ? "correct" : "wrong"} fade-in">
        <span class="verdict">${r.correct ? "🎉 정답입니다!" : "😅 아쉬워요, 오답입니다"}</span>
        ${r.correct ? "" : `<div style="margin-top:4px">정답: <b>${escapeHtml(r.correctAnswer ?? "")}</b></div>`}
        ${r.explanation ? `<div class="explain">${escapeHtml(r.explanation)}</div>` : ""}
        ${opts.reviewMode ? `<div class="explain" style="font-size:.82rem">${
          r.correct ? "복습 간격이 한 단계 늘어났어요. 다음엔 더 나중에 만나요 👋"
                    : "내일 다시 만나요. 오늘 틀린 건 내일이 복습 타이밍이에요 📅"}</div>` : ""}
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
            ${m.exp ? `<div class="exp">${escapeHtml(m.exp)}</div>` : ""}
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
