package project.study.study_project.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 닉네임 설정 요청.
 *
 * <p>글자를 좁힌 이유: 공백과 기호를 받으면 "민수"와 "민수 "처럼 눈으로 구분되지 않는 이름이 생긴다.
 */
public record NicknameRequest(
        @NotBlank(message = "닉네임을 입력해 주세요.")
        @Pattern(regexp = "^[가-힣A-Za-z0-9_]{2,12}$",
                message = "닉네임은 2~12자의 한글·영문·숫자·밑줄만 쓸 수 있습니다.")
        String nickname
) {
}
