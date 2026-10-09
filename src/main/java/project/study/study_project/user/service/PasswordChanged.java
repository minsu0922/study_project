package project.study.study_project.user.service;

/**
 * 비밀번호가 바뀌었다. 인증 쪽이 듣고 그 사용자의 refresh 토큰을 전부 끊는다(RefreshTokenStore).
 *
 * <p>이벤트인 이유: 인증이 사용자를 쓰는데 사용자가 다시 인증의 저장소를 부르면 두 패키지가 서로 기댄다.
 * 듣는 쪽은 동기 리스너라 발행한 메서드가 돌아오기 전에 끊긴다.
 */
public record PasswordChanged(Long userId) {
}
