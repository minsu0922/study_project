package project.study.study_project.auth.cookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * refresh 토큰을 싣는 쿠키. 화면 코드가 읽지 못하게 HttpOnly로 둔다 — 응답 본문으로 주면
 * 화면이 어딘가에 저장해야 하고, 스크립트 주입 한 번에 14일짜리 토큰이 넘어간다.
 *
 * <p>CSRF 토큰을 따로 두지 않는 이유: 이 쿠키를 자격으로 받는 곳은 재발급과 로그아웃 둘뿐이고,
 * {@code SameSite=Strict}라 다른 사이트에서 온 요청에는 실리지 않는다. 나머지 API는 지금처럼
 * Authorization 헤더만 본다.
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";

    /** 재발급·로그아웃이 있는 경로. 다른 요청에는 실려 가지 않는다. */
    public static final String PATH = "/api/auth";

    private final Duration validity;

    public RefreshTokenCookie(@Value("${jwt.refresh-token-validity-seconds}") long validitySeconds) {
        this.validity = Duration.ofSeconds(validitySeconds);
    }

    /** 토큰이 없으면(Redis 장애로 발급을 건너뜀) 옛 쿠키를 지운다 — 남겨 두면 다른 계정의 것일 수 있다. */
    public void issue(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        if (refreshToken == null) {
            clear(request, response);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, refreshToken, validity).toString());
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, "", Duration.ZERO).toString());
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private ResponseCookie build(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .sameSite("Strict")
                .path(PATH)
                // 로컬(http)에서 무조건 붙이면 쿠키가 저장되지 않는다(AdminGateCookie와 같은 판단).
                .secure(request.isSecure())
                .maxAge(maxAge)
                .build();
    }
}
