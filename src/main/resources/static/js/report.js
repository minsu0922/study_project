/* =====================================================================
 * csquiz 문제 오류 제보 — 화면 한 벌(V17)
 * ---------------------------------------------------------------------
 * 이 앱의 문제는 대부분 LLM이 만들고 사람이 검수해 등록된다. 만드는 쪽에는 되먹임
 * 통로가 있었지만(거절 사유 → 다음 생성 프롬프트, docs/14) <푸는 쪽>에는 없었다.
 * 검수를 통과하고 출제된 뒤에야 드러나는 결함 — 정답 표시가 틀렸다든지 해설이 딴
 * 문제 것이라든지 — 이 가장 값진 신호인데 주울 그릇이 없었던 셈이다.
 *
 * [왜 파일을 따로 두나]
 * 쓰는 곳이 둘이다(퀴즈 플레이어의 채점 결과, 오답노트의 카드). 한쪽에 적고 다른 쪽에
 * 복사하면 언젠가 한쪽만 고쳐진다 — 로그아웃 처리를 한 함수로 모은 것과 같은 판단
 * (api.js의 wireLogout 주석).
 *
 * [왜 채점 <뒤>에만 여나]
 * 해설과 정답을 읽어야 "이 문제 틀렸다"를 판단할 수 있다. 풀기 전에 열어 두면
 * "모르겠으니 신고"가 되어 신호가 잡음으로 바뀐다.
 *
 * [사용법]
 *   1) 붙일 자리에 reportBlock(problemId)가 돌려준 HTML을 그대로 넣는다
 *   2) 끝. 동작은 이 파일이 document에 한 번 걸어 둔 위임 처리기가 맡는다
 * 호출부가 따로 wire를 부르지 않는 이유: 목록처럼 <나중에 그려지는> 자리에 붙는데,
 * 그릴 때마다 wire를 부르게 하면 언젠가 빠뜨린 화면에서 버튼이 죽어 있게 된다.
 * ===================================================================== */

/**
 * 사유 목록 — 값은 서버의 ReportReason enum과 1:1이고 서버가 진실의 원천이다
 * (api.js의 DOMAINS·TYPES와 같은 방식).
 *
 * <b>문구가 서버의 label과 다른 것은 실수가 아니다.</b> 서버 쪽 문장("정답으로 표시된
 * 보기가 실제로는 틀렸다")은 <모델에게 갈> 말이라 서술형이고, 여기 문구는 <누르는 사람이>
 * 고르는 말이라 짧다. 읽는 사람이 다르면 문장도 달라야 한다.
 */
const REPORT_REASONS = [
  ["WRONG_ANSWER", "정답이 틀렸어요"],
  ["AMBIGUOUS", "정답이 둘로 읽혀요"],
  ["EXPLANATION_MISMATCH", "해설이 안 맞아요"],
  ["CONTRADICTS_DOCUMENT", "개념 문서와 달라요"],
  ["TYPO", "오타·깨진 표기"],
  ["OTHER", "그 밖의 문제"],
];

/**
 * 제보 입구 HTML — 붙일 자리에 그대로 넣으면 된다.
 *
 * <p>비로그인이면 <b>빈 문자열</b>을 돌려준다. 눌러 본 뒤에 "로그인하세요"를 만나는 것보다
 * 처음부터 없는 편이 낫다(내비게이션에서 권한별로 메뉴를 거르는 것과 같은 규칙).
 * 어차피 채점 자체가 로그인 필요라 플레이어에서는 늘 보이지만, 오답노트 같은 자리까지
 * 같은 판단을 되풀이하지 않게 여기서 한 번에 막는다.
 */
function reportBlock(problemId) {
  if (!isLoggedIn()) return "";
  return `
    <div class="report" data-report="${problemId}">
      <button type="button" class="report-open" data-report-open>이 문제가 이상한가요?</button>
      <div class="report-form" hidden>
        <div class="report-chips">
          ${REPORT_REASONS.map(([code, label]) =>
            `<button type="button" class="chip" data-report-reason="${code}">${escapeHtml(label)}</button>`).join("")}
        </div>
        <input class="report-detail" type="text" maxlength="500"
               placeholder="한 줄 덧붙이면 고치는 데 큰 도움이 됩니다 (선택)">
        <div class="report-actions">
          <button type="button" class="btn-sm" data-report-send>보내기</button>
          <button type="button" class="btn-sm btn-outline" data-report-cancel>취소</button>
        </div>
        <div class="report-msg" hidden></div>
      </div>
    </div>`;
}

/* ── 동작 (위임 처리기 한 벌) ─────────────────────────────────────────
 * 클릭을 document에서 한 번만 받는다. 블록마다 처리기를 걸면 플레이어가 문제를 넘길 때마다
 * 새로 걸리고, 지워진 DOM에 걸린 것들이 남는다. 어느 블록인지는 closest("[data-report]")로
 * 거슬러 올라가 찾는다 — 블록이 자기 상태(고른 사유)를 dataset에 들고 있어 전역 변수가 없다. */
document.addEventListener("click", async (e) => {
  const box = e.target.closest("[data-report]");
  if (!box) return;

  // 1) 입구 열기
  if (e.target.closest("[data-report-open]")) {
    box.querySelector(".report-open").hidden = true;
    box.querySelector(".report-form").hidden = false;
    return;
  }

  // 2) 사유 고르기 — 하나만 선택된다(라디오처럼). 칩을 쓴 이유는 선택지가 여섯뿐이라
  //    드롭다운보다 한눈에 들어오고, 누르는 횟수도 하나 적기 때문이다.
  const chip = e.target.closest("[data-report-reason]");
  if (chip) {
    box.querySelectorAll("[data-report-reason]").forEach(c => c.classList.remove("on"));
    chip.classList.add("on");
    box.dataset.reason = chip.dataset.reportReason;
    return;
  }

  // 3) 취소 — 고른 것을 지우고 닫는다. 남겨 두면 다시 열었을 때 지난번 선택이 켜져 있어
  //    "이미 보냈나?"로 읽힌다.
  if (e.target.closest("[data-report-cancel]")) {
    closeReportForm(box);
    return;
  }

  // 4) 보내기
  if (e.target.closest("[data-report-send]")) {
    await sendReport(box);
  }
});

/** 폼을 접고 고른 사유를 비운다. */
function closeReportForm(box) {
  delete box.dataset.reason;
  box.querySelectorAll("[data-report-reason]").forEach(c => c.classList.remove("on"));
  box.querySelector(".report-detail").value = "";
  showReportMsg(box, null);
  box.querySelector(".report-form").hidden = true;
  box.querySelector(".report-open").hidden = false;
}

/**
 * 접수 요청.
 *
 * <p><b>버튼을 먼저 잠근다.</b> 응답이 오기 전 두 번 누르면 두 번째는 서버의 UNIQUE 제약에
 * 걸려 "이미 제보한 문제입니다"가 뜬다 — 방금 보낸 사람에게는 영문 모를 말이다.
 *
 * <p>성공하면 블록을 통째로 감사 한 줄로 바꾼다. 폼을 남겨 두면 같은 문제를 또 보낼 수 있을
 * 것처럼 보이는데 실제로는 막혀 있다(한 사람당 한 번).
 */
async function sendReport(box) {
  const reason = box.dataset.reason;
  if (!reason) {
    showReportMsg(box, "어떤 점이 이상한지 골라 주세요.", true);
    return;
  }

  const sendBtn = box.querySelector("[data-report-send]");
  sendBtn.disabled = true;
  showReportMsg(box, null);

  try {
    await api("/api/me/problem-reports", {
      method: "POST",
      body: JSON.stringify({
        problemId: Number(box.dataset.report),
        reason,
        detail: box.querySelector(".report-detail").value,
      }),
    });
    box.innerHTML = `<div class="report-done">제보를 보냈습니다. 확인하고 고치겠습니다 🙏</div>`;
  } catch (err) {
    sendBtn.disabled = false;
    // REPORT_001(이미 제보함)은 실패가 아니라 <상태 안내>다. 서버가 준 문장을 그대로 쓰되
    // 빨간 경고로 칠하지 않는다 — 사용자가 뭘 잘못한 것이 아니다.
    showReportMsg(box, err.message, err.code !== "REPORT_001");
  }
}

/** 폼 아래 한 줄 안내. text가 null이면 감춘다. */
function showReportMsg(box, text, isError = false) {
  const el = box.querySelector(".report-msg");
  if (!el) return;
  if (!text) { el.hidden = true; el.textContent = ""; return; }
  el.textContent = text;
  el.classList.toggle("error", isError);
  el.hidden = false;
}
