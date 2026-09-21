package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotNull;
import project.study.study_project.llm.service.DomainSettingService;

/**
 * 분야 순서를 한 칸 옮기는 요청 — {@code POST /api/admin/domain-settings/{domain}/move}.
 *
 * <p>본문으로 방향을 받는 이유는 {@code AdminTopicQueueController.move}가 그러듯 쿼리스트링도
 * 가능했지만, 여기서는 테스트가 이미 JSON 본문({@code {"direction":"UP"}})을 기대한다
 * (task-9-brief 1단계) — 몸이 하나뿐인 값이라도 형식을 통일해 두면 다음에 필드가 늘어도
 * 주소를 다시 설계할 필요가 없다.
 *
 * <p>{@link DomainSettingService.Direction}을 그대로 쓴다 — {@code TopicQueueService.Direction}과
 * 달리 여기는 {@code TOP}이 없다(11줄뿐이라 쓸 자리가 없다). 남의 enum을 빌리면 그쪽에 값이
 * 늘 때마다 여기까지 흔들리므로 따로 둔다(task-9-brief 룰링 1).
 */
public record AdminDomainSettingMoveRequest(

        @NotNull(message = "이동 방향을 지정해 주세요.")
        DomainSettingService.Direction direction
) {
}
