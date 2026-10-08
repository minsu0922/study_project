package project.study.study_project.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 복구 코드로 비밀번호를 다시 정한다. 새 비밀번호 규칙은 가입·변경과 같다. */
public record PasswordResetRequest(
        @NotBlank(message = "아이디는 필수입니다.")
        @Size(max = 30)
        String username,

        @NotBlank(message = "복구 코드는 필수입니다.")
        @Size(max = 60)
        String recoveryCode,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다."
        )
        String newPassword
) {
}
