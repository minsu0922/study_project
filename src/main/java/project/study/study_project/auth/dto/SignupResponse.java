package project.study.study_project.auth.dto;

import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

/**
 * 회원가입 성공 응답 — API 스펙(docs/03): {@code {id, email, role}}.
 *
 * <p>엔티티({@link User})를 그대로 반환하지 않고 DTO로 변환하는 이유:
 * 비밀번호 해시 같은 민감/불필요 필드가 응답에 새어 나가지 않게 하고, API 응답 계약을
 * 엔티티 변경과 분리하기 위함(엔티티가 바뀌어도 응답 모양은 우리가 통제).
 */
public record SignupResponse(Long id, String email, Role role) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
