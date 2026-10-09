package project.study.study_project.admin.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 처리 기록 인터셉터를 건다. 공용 설정(WebMvcConfig)에 두면 공용 패키지가 관리 기능에 기댄다. */
@Configuration
@RequiredArgsConstructor
public class AdminAuditWebConfig implements WebMvcConfigurer {

    private final AdminAuditInterceptor adminAuditInterceptor;

    /** 관리 API 전체에 건다 — 새 관리 API를 만들어도 처리 기록에서 빠지지 않는다(AdminAuditInterceptor). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuditInterceptor).addPathPatterns("/api/admin/**");
    }
}
