package project.study.study_project.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 설정.
 *
 * <p>이게 있어야 엔티티의 {@code @CreatedDate}/{@code @LastModifiedDate} 필드가 저장·수정 시점에
 * 자동으로 채워진다(예: {@code User.createdAt}).
 *
 * <p>왜 별도 설정 클래스로 뺐나: {@code @EnableJpaAuditing}을 메인 애플리케이션 클래스에 붙이면
 * {@code @WebMvcTest} 같은 슬라이스 테스트에서도 JPA 관련 빈을 요구해 테스트가 무거워질 수 있다.
 * 설정을 분리해 두면 필요할 때만 로드된다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
