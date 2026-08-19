package project.study.study_project.auth.gate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import project.study.study_project.auth.jwt.JwtTokenProvider;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * 관리 화면(정적 HTML) 출입증 쿠키 — 발급·삭제·판독을 한곳에 모은다.
 *
 * <h2>왜 쿠키가 필요한가 — 헤더로는 파일을 못 막는다</h2>
 *
 * <p>이 앱의 인증은 JWT를 {@code Authorization} 헤더에 실어 보내는 방식이다. 그런데 브라우저가
 * {@code /admin/index.html}을 받아 올 때는 <b>우리 자바스크립트가 끼어들 자리가 없다</b> —
 * 주소창에 친 요청이든 {@code <script src>}든 헤더를 붙일 방법이 없다. 그래서 정적 파일에
 * {@code hasRole(ADMIN)}을 걸면 관리자 자신도 못 연다.
 *
 * <p>브라우저가 <b>알아서 들고 가는 것</b>은 쿠키뿐이다. 그래서 관리 화면 전용 쿠키를 하나 둔다.
 * 헤더는 API를 지키고, 쿠키는 파일을 지킨다 — 역할이 다른 두 개다.
 *
 * <h2>왜 새 토큰을 만들지 않고 access 토큰을 그대로 넣나</h2>
 *
 * <p>새 체계를 만들면 만료·폐기·회전 규칙이 두 벌이 되고, 언젠가 한쪽만 고쳐진다
 * ("로그아웃했는데 관리 화면은 계속 열리는" 상태가 그 결과다). access 토큰을 그대로 쓰면
 * 검증기({@link JwtTokenProvider})도, 만료 시각도, 권한 판정도 이미 있는 것을 재사용한다.
 *
 * <p><b>CSRF 걱정은 없다.</b> 이 쿠키는 <b>인증에 쓰이지 않는다</b> — API는 여전히 헤더만 본다.
 * 쿠키가 하는 일은 "이 파일을 내려줘도 되는가" 하나뿐이고, 파일을 받는 것은 부작용이 없다.
 * 게다가 {@code Path=/admin}이라 API 요청({@code /api/**})에는 실려 나가지도 않는다.
 */
@Component
@RequiredArgsConstructor
public class AdminGateCookie {

    /** 쿠키 이름. 필터와 발급부가 같은 값을 봐야 하므로 상수로 못 박는다. */
    public static final String NAME = "admin_gate";

    /**
     * 쿠키가 실려 갈 경로.
     *
     * <p>이 값이 곧 <b>무엇을 감출 수 있는가</b>의 경계다. 관리용 자바스크립트를 {@code /js/}에
     * 두면 이 경로에 걸리지 않아 누구나 내려받고, 그 안에 관리 API 경로가 다 적혀 있다.
     * 그래서 관리 화면의 스크립트도 {@code /admin/js/} 아래에 둔다.
     */
    public static final String PATH = "/admin";

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 로그인·재발급 응답에 쿠키를 싣는다. <b>관리자가 아니면 지운다</b>.
     *
     * <p>지우는 쪽이 중요하다: 같은 브라우저에서 관리자로 쓰다가 일반 계정으로 로그인하면
     * 옛 쿠키가 남아 관리 화면이 계속 열린다. "권한이 내려갔는데 화면은 그대로"인 상태다.
     *
     * <p>수명을 토큰 만료와 같게 맞춘다 — 쿠키만 오래 살아 있으면 <b>만료된 토큰으로도
     * 화면이 열리는</b> 상태가 되고, 그러면 화면은 열리는데 API가 전부 401인 이상한 경험이 된다.
     * 재발급({@code /api/auth/refresh})에서도 이 메서드를 부르므로 쓰는 동안에는 갱신된다.
     */
    public void issue(HttpServletRequest request, HttpServletResponse response, String accessToken) {
        if (!isAdminToken(accessToken)) {
            clear(request, response);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                build(request, accessToken, Duration.ofSeconds(jwtTokenProvider.getValiditySeconds())).toString());
    }

    /** 로그아웃 — 같은 이름·경로로 수명 0짜리를 덮어써야 브라우저가 지운다. */
    public void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(request, "", Duration.ZERO).toString());
    }

    /** 요청에 실려 온 출입증이 유효한 관리자 토큰인지. 필터가 쓴다. */
    public boolean isValid(HttpServletRequest request) {
        return read(request).map(this::isAdminToken).orElse(false);
    }

    /* ── 내부 ────────────────────────────────────────────────── */

    private ResponseCookie build(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                // 자바스크립트가 읽지 못하게 한다. 이 쿠키는 브라우저가 파일을 받을 때만 쓰이고
                // 화면 코드가 읽을 일이 없다 — 읽을 수 있게 두면 XSS 한 방에 출입증이 새어 나간다.
                .httpOnly(true)
                // 다른 사이트에서 온 요청에는 실리지 않는다. 관리 화면은 남의 페이지에 끼워 넣을
                // 일이 없으므로 가장 강한 설정을 쓴다.
                .sameSite("Strict")
                .path(PATH)
                // HTTPS일 때만 Secure를 붙인다. 로컬(http)에서 무조건 붙이면 쿠키가 저장되지 않아
                // 개발 중에는 관리 화면을 아예 못 연다 — 설정값을 따로 두는 대신 요청을 보고 정한다.
                .secure(request.isSecure())
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> NAME.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * 토큰이 유효하고 ROLE_ADMIN인지.
     *
     * <p>권한 판정을 문자열 비교로 직접 하지 않고 {@link JwtTokenProvider#getAuthentication}이
     * 만든 권한 목록을 보는 이유: 토큰에서 권한을 뽑는 규칙이 그쪽에 이미 있고, 두 곳에서
     * 각자 해석하면 언젠가 어긋난다(예: 역할 이름 접두사 규칙이 바뀌었을 때).
     */
    private boolean isAdminToken(String token) {
        if (token == null || token.isBlank() || !jwtTokenProvider.validateToken(token)) {
            return false;
        }
        Authentication authentication = jwtTokenProvider.getAuthentication(token);
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
