package project.study.study_project.admin.dto;

import java.util.List;

/**
 * 이미 나간 문서를 주제에 잇는 일의 결과.
 *
 * @param linked   주제에 이어진 문서 수(이미 이어져 있던 것도 센다)
 * @param unlinked 잇지 못한 사용 기록. 사람이 보고 손으로 잇는다
 */
public record DocumentTopicBackfillResult(int linked, List<Unlinked> unlinked) {

    /**
     * @param reason TOPIC_MISSING(대기열에 그 주제 없음), FILE_MISSING(그날 문서 파일 없음),
     *               DOMAIN_MISMATCH(파일 분야가 기록 분야와 다름), DOCUMENT_MISSING(파일의 slug로 문서를 못 찾음),
     *               ALREADY_LINKED(문서가 이미 다른 주제에 이어져 있어 덮지 않음)
     */
    public record Unlinked(Long topicId, String date, String reason) {
    }
}
