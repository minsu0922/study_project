package project.study.study_project.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 회원가입 요청 바디 — API 스펙(docs/03) 기준.
 *
 * <p>검증은 여기(요청 DTO)에서 애너테이션으로 수행한다. 실패하면 전역 예외처리가
 * {@code VALIDATION_ERROR}(400) + fieldErrors로 변환한다(docs/04).
 * <ul>
 *   <li>비밀번호 규칙(8자 이상 + 영문·숫자 포함)의 근거는 docs/06 참고.
 *   <li>정규식 {@code (?=.*[A-Za-z])}: 영문자 최소 1개, {@code (?=.*\d)}: 숫자 최소 1개,
 *       {@code .{8,}}: 전체 길이 8 이상. (?=...)는 "앞을 내다보는" 검사라 위치를 소비하지 않는다.
 * </ul>
 */
public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다."
        )
        String password
) {
}
