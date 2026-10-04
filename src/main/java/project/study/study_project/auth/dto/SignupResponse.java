package project.study.study_project.auth.dto;

import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

/**
 * 회원가입 성공 응답 — {@code {id, username, role}}(V12에서 email → username).
 *
 * <p>엔티티({@link User})를 그대로 반환하지 않고 DTO로 변환하는 이유:
 * 비밀번호 해시 같은 민감/불필요 필드가 응답에 새어 나가지 않게 하고, API 응답 계약을
 * 엔티티 변경과 분리하기 위함(엔티티가 바뀌어도 응답 모양은 우리가 통제).
 */
/**
 * @param tokens 가입과 함께 내주는 로그인 토큰. 화면이 로그인을 따로 부르지 않게 하려는 것이다 —
 *               따로 부르면 인증 요청 제한(분당 5회)을 하나 더 써서, 닉네임이 겹쳐 몇 번 실패한
 *               사람은 가입에 성공하고도 로그인이 막힌다
 */
public record SignupResponse(Long id, String username, Role role, LoginResponse tokens) {

    public static SignupResponse of(User user, LoginResponse tokens) {
        return new SignupResponse(user.getId(), user.getUsername(), user.getRole(), tokens);
    }
}
