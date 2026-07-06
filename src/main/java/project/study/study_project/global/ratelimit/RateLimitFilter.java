package project.study.study_project.global.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ApiError;
import project.study.study_project.global.response.ApiResponse;

import java.io.IOException;
import java.util.Set;

/**
 * 요청 제한 필터 — 로드맵 3. 설계는 docs/09, 결정 배경은 ADR-0003.
 *
 * <p><b>왜 필터인가(인터셉터·AOP가 아니라)</b>: 제한에 걸린 요청은 컨트롤러·서비스·DB에
 * 아예 도달하지 못하게 입구에서 끊는 게 목적이다. 필터는 서블릿 처리 파이프라인의 가장
 * 바깥 층이라 차단 비용이 최소다(Redis 왕복 1회뿐).
 *
 * <p><b>왜 JWT 필터 뒤에 두나</b>: 로그인 사용자는 IP가 아니라 <b>사용자 id로 세는 게</b>
 * 공정하기 때문(회사·학교는 수백 명이 IP 하나를 공유한다 — NAT). 사용자를 알려면 토큰이
 * 먼저 해석돼 있어야 한다. 그 대가로 제한 전에 JWT 검증 비용을 치르지만, HMAC 검증은
 * 메모리 연산이라 충분히 싸다.
 *
 * <p><b>거부 응답</b>: 429 Too Many Requests + {@code Retry-After} 헤더(표준, RFC 6585) +
 * 공통 응답 봉투의 {@code COMMON_429}. 필터 단계라 @RestControllerAdvice가 못 잡으므로
 * JwtAuthenticationEntryPoint와 같은 방식으로 JSON을 직접 쓴다.
 *
 * <p>JwtAuthenticationFilter와 같은 이유로 @Component를 붙이지 않고 SecurityConfig에서
 * 직접 생성한다(서블릿 컨테이너 자동 등록으로 인한 이중 실행 방지).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * 엄격한 auth 정책을 적용할 경로 (전부 POST).
     * 로그인·회원가입·재발급만 — 이들은 자격 증명을 "맞혀 볼 수 있는" 공격 표면이다.
     * 로그아웃은 맞혀서 얻을 게 없으므로 일반 api 정책으로 충분하다.
     */
    private static final Set<String> AUTH_PATHS =
            Set.of("/api/auth/login", "/api/auth/signup", "/api/auth/refresh");

    private final TokenBucketRateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter,
                           RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * API 요청에만 적용한다. 정적 파일(HTML/CSS/JS)·Swagger는 서버 자원을 거의 안 쓰고,
     * 여기서 걸리면 "페이지 자체가 안 열리는" 과잉 차단이 되므로 제외.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean isAuthPath = "POST".equals(request.getMethod())
                && AUTH_PATHS.contains(request.getRequestURI());

        RateLimitPolicy policy;
        String bucketKey;
        if (isAuthPath) {
            // 인증 경로는 "아직 누군지 모르는" 요청이므로 IP로 셀 수밖에 없다.
            // (공격자는 로그인 전이니 사용자 id가 없다)
            policy = properties.getAuthPolicy();
            bucketKey = "ip:" + clientIp(request);
        } else {
            policy = properties.getApiPolicy();
            Long userId = authenticatedUserId();
            // 로그인 사용자는 id로(NAT 뒤 다수 사용자의 공정성 + IP를 바꿔도 한도 회피 불가),
            // 비로그인은 IP로 센다.
            bucketKey = (userId != null) ? "user:" + userId : "ip:" + clientIp(request);
        }

        RateLimitResult result = rateLimiter.tryConsume(bucketKey, policy);
        if (result.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 429 + Retry-After: "언제 다시 와도 되는지"까지 알려 주는 게 표준 매너다.
        // 잘 만든 클라이언트는 이 값을 보고 백오프(대기 후 재시도)한다.
        ErrorCode code = ErrorCode.COMMON_429;
        response.setStatus(code.getHttpStatus().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(ApiError.of(code.getCode(), code.getDefaultMessage()));
        objectMapper.writeValue(response.getWriter(), body);
    }

    /** 인증된 사용자 id. JwtAuthenticationFilter가 principal에 심어 둔 값(Long). 미인증이면 null. */
    private Long authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 이 필터는 AnonymousAuthenticationFilter보다 앞이라 미인증이면 auth가 null이다.
        // (익명 토큰이 들어오는 경우까지 방어적으로 principal 타입을 확인한다)
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    /**
     * 클라이언트 IP. 리버스 프록시(nginx 등) 뒤에 배포되면 remoteAddr은 프록시 IP가 되므로
     * 프록시가 넣어 주는 X-Forwarded-For의 첫 값(원 클라이언트)을 우선한다.
     *
     * <p>한계(알고 쓰기): 프록시 없이 직접 노출된 서버라면 이 헤더는 클라이언트가 마음대로
     * 위조할 수 있다. 실 배포에서는 "신뢰하는 프록시가 덮어쓴 값만 믿는" 구성이 전제다
     * (docs/09에 기록). 지금은 로컬/단일 서버라 실질 영향 없음.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
