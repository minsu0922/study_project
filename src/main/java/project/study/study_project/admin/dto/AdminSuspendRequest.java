package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param days   정지 일수 — 1, 7, 30 가운데 하나. {@code null}이면 무기한이다
 * @param reason 정지 사유. 정지된 사람에게 그대로 보이므로 비울 수 없다
 */
public record AdminSuspendRequest(
        Integer days,
        @NotBlank(message = "정지 사유를 적어 주세요.")
        @Size(max = 200, message = "정지 사유는 200자를 넘을 수 없습니다.")
        String reason
) {
}
