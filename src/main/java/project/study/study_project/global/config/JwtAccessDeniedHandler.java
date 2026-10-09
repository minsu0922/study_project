package project.study.study_project.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.ErrorResponseWriter;

import java.io.IOException;

/**
 * <b>인증은 됐지만 권한이 부족한</b> 요청일 때 호출된다 → 403 {@code AUTH_004}.
 * (예: USER가 ADMIN 전용 리소스에 접근)
 *
 * <p>{@link JwtAuthenticationEntryPoint}와 마찬가지로 필터 단계라 전역 예외처리가 못 잡으므로
 * 동일 응답 봉투(docs/04)를 직접 만들어 내려 준다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private static final String ADMIN_API_PREFIX = "/api/admin/";

    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (request.getRequestURI().startsWith(ADMIN_API_PREFIX)) {
            try {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                Long userId = auth != null && auth.getPrincipal() instanceof Long id ? id : null;
                eventPublisher.publishEvent(new AdminAccessDenied(userId, request.getMethod(),
                        request.getRequestURI()));
            } catch (RuntimeException e) {
                // 기록에 실패해도 거부 응답은 나가야 한다
                log.warn("관리 API 접근 거부를 알리지 못했습니다: {}", e.getMessage());
            }
        }
        ErrorResponseWriter.write(response, objectMapper, ErrorCode.AUTH_004);
    }
}
