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
        @Pattern(regexp = USERNAME_PATTERN, message = USERNAME_MESSAGE)
        String username,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                message = "비밀번호는 8자 이상이며 영문과 숫자를 포함해야 합니다."
        )
        String password,

        // 토론에서 글쓴이로 보이는 이름(V21). 가입할 때 받는다 — 글을 쓰다 말고 정하게 하면
        // 흐름이 끊기고, 닉네임 없는 계정이 계속 쌓인다. 규칙은 NicknameRequest와 같다.
        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(regexp = NICKNAME_PATTERN, message = NICKNAME_MESSAGE)
        String nickname
) {

    /*
     * 형식 규칙과 그 안내 문구. 상수로 뺀 이유: 가입 전에 "쓸 수 있는지"를 묻는 조회
     * (AuthService.checkAvailability)가 같은 규칙으로 답해야 한다. 따로 적으면 조회는 된다고
     * 했는데 가입은 거절하는 값이 생긴다.
     */
    public static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{4,20}$";
    public static final String USERNAME_MESSAGE = "아이디는 영문·숫자·밑줄(_)로 4~20자여야 합니다.";
    public static final String NICKNAME_PATTERN = "^[가-힣A-Za-z0-9_]{2,12}$";
    public static final String NICKNAME_MESSAGE = "닉네임은 2~12자의 한글·영문·숫자·밑줄만 쓸 수 있습니다.";
}
