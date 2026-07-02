package project.study.study_project.auth.dto;

/**
 * 로그인 성공 응답(토큰) — API 스펙(docs/03): {@code {accessToken, tokenType, expiresIn}}.
 *
 * @param accessToken 발급된 JWT access 토큰
 * @param tokenType   항상 {@code "Bearer"} (클라이언트가 {@code Authorization: Bearer <token>}로 사용)
 * @param expiresIn   만료까지 남은 초(예 3600 = 1시간). MVP는 refresh 토큰 없음(ADR-0001).
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    public static LoginResponse of(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, "Bearer", expiresIn);
    }
}
