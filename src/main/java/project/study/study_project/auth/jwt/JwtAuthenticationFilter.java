package project.study.study_project.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 매 요청마다 한 번 실행되며, {@code Authorization: Bearer <token>} 헤더의 JWT를 검증해
 * 유효하면 인증 정보를 SecurityContext에 심는다 — 설계는 docs/06.
 *
 * <p>동작 원칙:
 * <ul>
 *   <li>토큰이 없거나 유효하지 않으면 <b>아무 것도 하지 않고 그냥 통과</b>시킨다.
 *       이후 인가 단계에서 보호 리소스면 SecurityContext가 비어 있어 401(AUTH_003)로 처리된다.
 *       (공개 리소스는 인증 없이도 정상 진행)
 *   <li>{@link OncePerRequestFilter}를 상속해 forward/에러 디스패치 등으로 중복 실행되는 것을 막는다.
 * </ul>
 * <p>이 필터는 {@code SecurityConfig}에서 직접 생성해 체인에 등록한다(@Component로 두면 서블릿
 * 컨테이너에도 자동 등록돼 이중 실행될 수 있어 일부러 빈으로 만들지 않는다).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Authentication authentication = jwtTokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    /** Authorization 헤더에서 "Bearer " 접두어를 떼고 토큰만 뽑는다. 없으면 null. */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
