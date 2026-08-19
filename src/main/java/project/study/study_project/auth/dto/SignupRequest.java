package project.study.study_project.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 회원가입 요청 바디 — API 스펙(docs/03) 기준.
 *
 * <p>검증은 여기(요청 DTO)에서 애너테이션으로 수행한다. 실패하면 전역 예외처리가
 * {@code VALIDATION_ERROR}(400) + fieldErrors로 변환한다(docs/04).
 * <ul>
 *   <li>아이디 규칙(영문·숫자·밑줄 4~20자): 이메일을 대신하는 로그인 아이디라 <b>로그·주소·
 *       터미널에서 깨지지 않는 글자</b>로 제한한다. 한글을 허용하면 기억하기는 쉽지만
 *       이 프로젝트는 이미 한글 경로 때문에 빌드가 깨지는 문제를 겪고 있다(CLAUDE.md).
 *       하이픈을 뺀 것은 나중에 아이디가 주소에 들어갈 때 구분자와 헷갈리지 않게 하려는 것.
 *   <li>비밀번호 규칙(8자 이상 + 영문·숫자 포함)의 근거는 docs/06 참고.
 *   <li>정규식 {@code (?=.*[A-Za-z])}: 영문자 최소 1개, {@code (?=.*\d)}: 숫자 최소 1개,
 *       {@code .{8,}}: 전체 길이 8 이상. (?=...)는 "앞을 내다보는" 검사라 위치를 소비하지 않는다.
 * </ul>
 *
 * <p>대문자를 <b>막지 않고 받아서 소문자로 낮춰</b> 저장한다({@code AuthService}) —
 * 막으면 "왜 안 되지"를 겪게 되고, 그대로 저장하면 "Minsu로 가입하고 minsu로 로그인"이
 * 실패한다. 받아서 정규화하는 쪽이 둘 다 피한다.
 */
public record SignupRequest(

        @NotBlank(message = "아이디는 필수입니다.")
        @Pattern(
                regexp = "^[A-Za-z0-9_]{4,20}$",
                message = "아이디는 영문·숫자·밑줄(_)로 4~20자여야 합니다."
        )
        String username,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다."
        )
        String password
) {
}
