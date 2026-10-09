package project.study.study_project.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 로그인/재발급 성공 응답(토큰 묶음) — docs/03 + 로드맵 2(refresh 토큰) 확장.
 *
 * @param accessToken  발급된 JWT access 토큰 (1시간 — 매 요청의 신분증)
 * @param refreshToken 재발급용 불투명 토큰 (14일, Redis 보관 — RefreshTokenStore 참고).
 *                     Redis 장애 시 null일 수 있다(그땐 access 만료 시 재로그인).
 *                     <b>응답 본문에는 싣지 않는다</b> — 컨트롤러가 HttpOnly 쿠키로 옮긴다(RefreshTokenCookie).
 * @param tokenType    항상 {@code "Bearer"} (클라이언트가 {@code Authorization: Bearer <token>}로 사용)
 * @param expiresIn    access 토큰 만료까지 남은 초(예 3600 = 1시간)
 */
public record LoginResponse(String accessToken, @JsonIgnore String refreshToken, String tokenType,
                            long expiresIn) {

    public static LoginResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn);
    }
}
