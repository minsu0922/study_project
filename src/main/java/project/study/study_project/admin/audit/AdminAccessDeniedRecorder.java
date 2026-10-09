package project.study.study_project.admin.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import project.study.study_project.global.config.AdminAccessDenied;

/**
 * 권한 없이 관리 API를 부른 시도를 처리 기록에 적는다(V38). 조회도 적는다 —
 * 관리자가 아닌 사람이 관리 API를 읽으려 한 것 자체가 봐야 할 일이다.
 *
 * <p>이런 요청은 컨트롤러에 닿기 전에 막혀 {@link AdminAuditInterceptor}가 보지 못한다.
 * 주소 틀을 알 수 없어 pattern 칸에도 불린 주소를 그대로 적는다.
 */
@Component
public class AdminAccessDeniedRecorder {

    private final AdminAuditService auditService;
    /** 테스트에서 끄는 스위치. 이유는 {@link AdminAuditInterceptor}와 같다. */
    private final boolean enabled;

    public AdminAccessDeniedRecorder(AdminAuditService auditService,
                                     @Value("${admin.audit.enabled:true}") boolean enabled) {
        this.auditService = auditService;
        this.enabled = enabled;
    }

    @EventListener
    public void on(AdminAccessDenied event) {
        if (enabled) {
            auditService.record(event.userId(), event.method(), event.path(), event.path(), null,
                    HttpStatus.FORBIDDEN.value());
        }
    }
}
