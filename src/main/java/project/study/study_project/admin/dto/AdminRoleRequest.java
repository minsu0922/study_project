package project.study.study_project.admin.dto;

import jakarta.validation.constraints.NotNull;
import project.study.study_project.user.domain.Role;

/** @param role 바꿀 권한 — {@code USER} 또는 {@code ADMIN} */
public record AdminRoleRequest(
        @NotNull(message = "바꿀 권한을 골라 주세요.")
        Role role
) {
}
