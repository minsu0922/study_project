package project.study.study_project.global.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilter 단위 테스트 — "어떤 요청에, 어떤 정책과 키로, 거부 시 어떤 응답을 주는가".
 *
 * <p>버킷 판정 자체(Lua)는 {@link TokenBucketRateLimiterTest}가 실제 Redis로 검증하므로,
 * 여기서는 limiter를 mock으로 두고 필터의 <b>분기 로직만</b> 본다 — 각 테스트가 한 가지
 * 책임만 검증하게 경계를 나눈 것(그래서 Redis 없이도 이 테스트는 항상 돈다).
 */
class RateLimitFilterTest {

    private TokenBucketRateLimiter limiter;
    private RateLimitFilter filter;

    // 운영 기본값과 같은 정책: auth 5/분, api 60/분, enabled=true
    private RateLimitProperties props(boolean enabled) {
        return new RateLimitProperties(enabled, 5, 5, 60, 60, 60, 60);
    }

    @BeforeEach
    void setUp() {
        limiter = mock(TokenBucketRateLimiter.class);
        filter = new RateLimitFilter(limiter, props(true), new ObjectMapper());
        // 기본은 허용 — 거부 케이스는 개별 테스트에서 재정의
        when(limiter.tryConsume(anyString(), any())).thenReturn(new RateLimitResult(true, 0));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext(); // 테스트 간 인증 상태 누수 방지
    }

    private MockHttpServletResponse doFilter(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("/api/ 밖(정적 파일 등)은 제한을 아예 타지 않는다")
    void staticResourcesBypass() throws Exception {
        doFilter(new MockHttpServletRequest("GET", "/login.html"));
        verify(limiter, never()).tryConsume(anyString(), any());
    }

    @Test
    @DisplayName("enabled=false면 API 요청도 제한 없이 통과한다")
    void disabledBypass() throws Exception {
        filter = new RateLimitFilter(limiter, props(false), new ObjectMapper());
        doFilter(new MockHttpServletRequest("POST", "/api/auth/login"));
        verify(limiter, never()).tryConsume(anyString(), any());
    }

    @Test
    @DisplayName("로그인 POST는 auth 정책 + IP 키로 센다")
    void loginUsesAuthPolicyKeyedByIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.7");
        doFilter(request);

        ArgumentCaptor<RateLimitPolicy> policy = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter).tryConsume(eq("ip:10.0.0.7"), policy.capture());
        assertThat(policy.getValue().name()).isEqualTo("auth");
    }

    @Test
    @DisplayName("로그인 사용자의 일반 API는 IP가 아니라 사용자 id로 센다")
    void authenticatedApiKeyedByUserId() throws Exception {
        // JwtAuthenticationFilter가 심어 주는 것과 같은 형태(principal=Long userId)로 흉내낸다.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        42L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        doFilter(new MockHttpServletRequest("GET", "/api/me/wrong-answers"));

        ArgumentCaptor<RateLimitPolicy> policy = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter).tryConsume(eq("user:42"), policy.capture());
        assertThat(policy.getValue().name()).isEqualTo("api");
    }

    @Test
    @DisplayName("비로그인 일반 API는 api 정책 + IP 키로 센다")
    void anonymousApiKeyedByIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.setRemoteAddr("10.0.0.8");
        doFilter(request);

        ArgumentCaptor<RateLimitPolicy> policy = ArgumentCaptor.forClass(RateLimitPolicy.class);
        verify(limiter).tryConsume(eq("ip:10.0.0.8"), policy.capture());
        assertThat(policy.getValue().name()).isEqualTo("api");
    }

    @Test
    @DisplayName("프록시 경유 시 X-Forwarded-For의 첫 IP(원 클라이언트)를 쓴다")
    void forwardedForTakesPrecedence() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("172.17.0.1"); // 프록시 IP
        request.addHeader("X-Forwarded-For", "203.0.113.9, 172.17.0.1");
        doFilter(request);

        verify(limiter).tryConsume(eq("ip:203.0.113.9"), any());
    }

    @Test
    @DisplayName("거부되면 429 + Retry-After 헤더 + 공통 봉투(COMMON_429)로 응답한다")
    void deniedProduces429Envelope() throws Exception {
        when(limiter.tryConsume(anyString(), any())).thenReturn(new RateLimitResult(false, 12));

        MockHttpServletResponse response = doFilter(new MockHttpServletRequest("GET", "/api/documents"));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
        // 응답 본문이 다른 API 에러와 똑같은 봉투인지 — 클라이언트가 특수 처리 없이 파싱 가능해야 한다.
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("\"code\":\"COMMON_429\"");
    }

    @Test
    @DisplayName("허용되면 다음 필터로 그대로 통과한다 (응답에 손대지 않음)")
    void allowedPassesThrough() throws Exception {
        MockHttpServletResponse response = doFilter(new MockHttpServletRequest("GET", "/api/documents"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Retry-After")).isNull();
    }
}
