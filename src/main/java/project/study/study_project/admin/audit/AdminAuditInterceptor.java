package project.study.study_project.admin.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 관리 API의 쓰기 요청이 성공하면 처리 기록을 남긴다(V35).
 *
 * <p><b>서비스마다 적지 않고 여기 한 곳에서 적는 이유</b>: 메서드마다 "기록 남기기" 한 줄을 넣는
 * 방식은 새 API를 만들 때 그 한 줄을 빠뜨리면 조용히 기록이 비고, 빠진 줄은 아무도 못 본다.
 * 경로 규칙(/api/admin/**)으로 걸면 빠뜨릴 수가 없다 — 권한을 SecurityConfig 한 곳에서 거는 것과 같다.
 *
 * <p><b>치르는 값</b>: 본 작업과 다른 트랜잭션이다. 본 작업이 끝난 뒤 기록에 실패하면 작업은 됐는데
 * 기록이 없다. 그 경우 경고 로그를 남기고 요청은 성공으로 둔다 — 기록 때문에 이미 끝난 정지를
 * 실패로 알리면 관리자가 같은 일을 한 번 더 한다.
 */
@Slf4j
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {

    private final AdminAuditService auditService;
    /**
     * 테스트에서 끄는 스위치. 롤백되지 않는 테스트가 관리 API를 부르면 개발 DB에 기록이 쌓인다 —
     * 기록은 지우는 길이 없어서 한 번 쌓이면 손으로 치워야 한다.
     */
    private final boolean enabled;

    public AdminAuditInterceptor(AdminAuditService auditService,
                                 @Value("${admin.audit.enabled:true}") boolean enabled) {
        this.auditService = auditService;
        this.enabled = enabled;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 조회는 적지 않는다. 목록을 열 때마다 한 줄씩 쌓이면 정작 봐야 할 줄이 묻힌다.
        if (!enabled || "GET".equals(request.getMethod()) || response.getStatus() >= 300) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            return;
        }
        try {
            auditService.record(currentUserId(), request.getMethod(), pattern.toString(), request.getRequestURI());
        } catch (RuntimeException e) {
            log.warn("처리 기록을 남기지 못했습니다: {} {} — {}", request.getMethod(), request.getRequestURI(),
                    e.getMessage());
        }
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long userId ? userId : null;
    }
}
