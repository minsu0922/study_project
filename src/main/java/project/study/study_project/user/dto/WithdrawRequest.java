package project.study.study_project.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 탈퇴 요청 (2026-09-08).
 *
 * <p>비밀번호를 받는다. 탈퇴는 <b>되돌릴 수 없고</b>, 이 앱에는 복구 수단이 없다 —
 * 잘못 눌러도 관리자가 되살려 줄 방법이 없으므로 문턱이 있어야 한다.
 *
 * <p>형식은 검사하지 않는다({@code @Pattern}이 없다). 로그인과 같은 이유다 —
 * 형식 오류를 친절히 알려 주면 "이 비밀번호는 형식이 맞다"는 사실만으로 후보를 좁혀 준다.
 * 맞는지 아닌지는 서비스가 판단하고, 틀리면 한 가지 답만 준다.
 */
public record WithdrawRequest(
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {
}
