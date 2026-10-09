package project.study.study_project.auth.dto;

/**
 * 토큰 재발급/로그아웃 요청 바디 — 쿠키가 없을 때만 본다.
 *
 * <p>refresh 토큰은 쿠키로 온다(RefreshTokenCookie). 이 바디는 쿠키로 옮기기 전에 로그인해
 * 토큰을 localStorage에 들고 있는 브라우저를 한 번 받아 주는 자리다. 그 브라우저도 첫 재발급에서
 * 쿠키를 받는다.
 */
public record RefreshRequest(String refreshToken) {
}
