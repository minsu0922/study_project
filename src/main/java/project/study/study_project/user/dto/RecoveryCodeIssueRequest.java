package project.study.study_project.user.dto;

import jakarta.validation.constraints.NotBlank;

/** 복구 코드 재발급. 토큰만으로는 내주지 않는다 — 비밀번호 변경·탈퇴와 같은 규칙. */
public record RecoveryCodeIssueRequest(
        @NotBlank(message = "지금 비밀번호는 필수입니다.")
        String currentPassword
) {
}
