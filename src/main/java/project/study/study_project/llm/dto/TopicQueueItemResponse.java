package project.study.study_project.llm.dto;

import project.study.study_project.global.common.Domain;
import project.study.study_project.llm.domain.TopicQueueItem;

import java.time.LocalDate;

/**
 * 주제 대기열 한 줄의 화면용 표현.
 *
 * <p>{@code domainLabel}(한글 이름)을 서버가 함께 내려보내는 것은 이 프로젝트의 기존 규칙이다
 * — 프런트가 상수명→한글 표를 따로 들고 있으면 분야를 하나 추가할 때 두 곳을 고쳐야 하고,
 * 언젠가 한쪽만 고쳐진다.
 *
 * @param pending 아직 안 쓴 주제인지. {@code usedAt == null}로 화면에서 판정할 수도 있지만,
 *                "무엇이 대기 중인가"는 이 기능의 핵심 개념이라 이름을 붙여 내려보낸다
 */
public record TopicQueueItemResponse(
        Long id,
        Domain domain,
        String domainLabel,
        String topic,
        String memo,
        int sortOrder,
        LocalDate usedAt,
        boolean pending
) {

    public static TopicQueueItemResponse from(TopicQueueItem item) {
        return new TopicQueueItemResponse(
                item.getId(), item.getDomain(), item.getDomain().getDisplayName(),
                item.getTopic(), item.getMemo(), item.getSortOrder(),
                item.getUsedAt(), item.isPending());
    }
}
