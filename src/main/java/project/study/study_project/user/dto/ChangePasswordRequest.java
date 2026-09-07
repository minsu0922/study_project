package project.study.study_project.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 비밀번호 변경 요청 (2026-09-08).
 *
 * <p><b>지금 비밀번호를 함께 받는다.</b> 토큰이 있다는 것은 "이 브라우저가 로그인했다"이지
 * "지금 앉아 있는 사람이 본인이다"가 아니다. 잠깐 자리를 비운 사이 남이 만지면, 확인 절차가
 * 없을 때 비밀번호가 바뀌어 계정을 통째로 빼앗긴다.
 *
 * <p><b>새 비밀번호 규칙은 가입과 글자 하나까지 같다.</b> 변경만 느슨하면 그 문으로 약한
 * 비밀번호가 들어온다 — 규칙이 두 벌이면 반드시 한쪽이 헐거워진다.
 * ({@code SignupRequest}와 같은 정규식이다. 상수로 묶지 않은 이유는 그쪽 주석과 같다 —
 * 가입과 변경의 규칙이 갈릴 이유가 언젠가 생길 수 있고, 그때 한 곳을 고쳐 둘 다 바뀌는
 * 것보다 각자 적혀 있는 편이 안전하다.)
 */
public record ChangePasswordRequest(
        @NotBlank(message = "지금 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다."
        )
        String newPassword
) {
}
