package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 일괄 승인 결과 — <b>부분 성공을 그대로 드러내는</b> 응답.
 *
 * <h2>왜 200 + 결과 목록인가 (실패가 하나라도 있으면 4xx가 아니라)</h2>
 *
 * <p>HTTP 상태 코드는 요청 하나의 성패를 말하는 자리인데, 일괄 승인은 성패가 <b>건마다</b>
 * 다르다. 10건 중 1건이 실패했을 때 400을 주면 프론트는 "9건은 어떻게 됐나"를 알 수 없고,
 * 200만 주면 실패를 조용히 삼킨다. 그래서 요청 자체는 성공(200)으로 두고, 무엇이 되고
 * 무엇이 안 됐는지를 본문에 적는다. 화면은 이 목록을 그대로 사람에게 보여 준다.
 *
 * <p>이 판단은 초안 저장({@code LlmProblemService.saveDrafts})의 "5문제 중 1개가 이상하다고
 * 나머지 4개를 버리지 않는다"와 같은 원칙이다. 다만 저장 쪽은 버린 것을 로그에만 남기고,
 * 여기서는 <b>사람에게 돌려준다</b> — 검수는 사람이 다시 손댈 수 있는 작업이라 그렇다.
 *
 * @param approved 성공한 건 — 어느 초안이 어느 문제가 됐는지까지 알려 준다(바로 열어 볼 수 있게)
 * @param failed   실패한 건 — 사유를 사람이 읽을 문장으로 담는다
 */
public record LlmBulkApproveResponse(
        List<Approved> approved,
        List<Failed> failed
) {

    /**
     * @param draftId   승인한 초안 ID
     * @param problemId 그 결과로 만들어진 정식 문제 ID
     */
    public record Approved(Long draftId, Long problemId) {
    }

    /**
     * @param draftId 실패한 초안 ID
     * @param message 실패 사유. {@code BusinessException}의 메시지를 그대로 옮긴다 —
     *                이미 사람이 읽을 문장으로 쓰여 있어 다시 꾸밀 이유가 없다
     */
    public record Failed(Long draftId, String message) {
    }

    /** 전부 성공했는지 — 화면이 "3건 승인 완료"와 "2건 실패" 중 무엇을 띄울지 고르는 데 쓴다. */
    public boolean allSucceeded() {
        return failed.isEmpty();
    }
}
