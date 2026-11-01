package project.study.study_project.llm.dto;

import java.util.List;

/**
 * 문서 초안 일괄 승인 결과. 부분 성공을 그대로 드러내는 이유는 {@link LlmBulkApproveResponse}와 같다.
 * 문제 쪽과 따로 둔 까닭은 성공 칸이 다르기 때문이다. 화면이 바로 문서를 열 수 있게 slug를 함께 준다.
 */
public record LlmDocumentBulkApproveResponse(
        List<Approved> approved,
        List<LlmBulkApproveResponse.Failed> failed
) {

    /**
     * @param draftId    승인한 초안 ID
     * @param documentId 그 결과로 만들어진 정식 문서 ID
     * @param slug       만들어진 문서의 주소 이름
     */
    public record Approved(Long draftId, Long documentId, String slug) {
    }

    public boolean allSucceeded() {
        return failed.isEmpty();
    }
}
