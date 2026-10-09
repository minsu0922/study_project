package project.study.study_project.global.config;

/**
 * 권한이 없는 사람이 관리 API를 불렀다 — {@link JwtAccessDeniedHandler}가 알리고 처리 기록이 받아 적는다.
 *
 * <p>이벤트로 알리는 이유: 이 패키지가 처리 기록(admin)을 직접 부르면 공용 설정이 기능 패키지에 기댄다.
 *
 * @param userId 토큰의 사용자. 토큰에서 못 읽었으면 {@code null}
 */
public record AdminAccessDenied(Long userId, String method, String path) {
}
