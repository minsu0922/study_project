package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.TopicQueueItem;

import java.time.LocalDate;

/**
 * 주제 범위 한 줄의 화면용 표현.
 *
 * <p>{@code domainLabel}(한글 이름)을 서버가 함께 내려보내는 것은 이 프로젝트의 기존 규칙이다
 * — 프런트가 상수명→한글 표를 따로 들고 있으면 분야를 하나 추가할 때 두 곳을 고쳐야 하고,
 * 언젠가 한쪽만 고쳐진다.
 *
 * @param usedCount 이 범위로 만든 문서 편수. 화면에서 <b>범위가 말라 가는 것</b>을 알아채는
 *                  유일한 신호라 목록에 반드시 띄운다
 * @param next      다음 문서일에 쓰일 범위인지. 판정 규칙(안 쓴 것 먼저 → 가장 오래된 것)이
 *                  화면과 배치 양쪽에 있으면 언젠가 어긋나므로, <b>서버가 계산해</b> 내려보낸다
 */
public record TopicQueueItemResponse(
        Long id,
        Domain domain,
        String domainLabel,
        String topic,
        String memo,
        int sortOrder,
        LocalDate lastUsedAt,
        int usedCount,
        boolean next
) {

    public static TopicQueueItemResponse from(TopicQueueItem item, boolean next) {
        return new TopicQueueItemResponse(
                item.getId(), item.getDomain(), item.getDomain().getDisplayName(),
                item.getTopic(), item.getMemo(), item.getSortOrder(),
                item.getLastUsedAt(), item.getUsedCount(), next);
    }
}
