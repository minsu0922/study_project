package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 고른 범위들을 한꺼번에 맨 위로 보내는 요청 — 2026-09-14 신설.
 *
 * <p><b>순서는 서버가 정한다.</b> 여기 담긴 id 순서는 무시되고, 고른 것들끼리의 상대 순서는
 * <b>지금 대기열 순서</b>가 그대로 간다({@code TopicQueueService.moveToTop}). 화면이 체크한
 * 순서를 실어 보내도 결과는 같다 — 고르는 일과 순서를 정하는 일을 갈라 둔 것이라,
 * 이 record가 순서까지 나른다고 읽히지 않게 여기에 적어 둔다.
 *
 * @param ids 옮길 범위의 id. 없는 id가 섞여 있어도 나머지는 옮겨진다
 */
public record AdminTopicQueueMoveRequest(

        @NotEmpty(message = "옮길 범위를 하나 이상 고르세요.")
        List<Long> ids
) {
}
