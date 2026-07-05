package project.study.study_project.global.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * QueryDSL 설정 — {@link JPAQueryFactory}를 빈으로 등록해 리포지토리 구현체들이 주입받게 한다.
 *
 * <p>JPAQueryFactory는 QueryDSL 쿼리의 시작점(팩터리)이다. EntityManager를 감싸는 얇은 객체라
 * 매번 new 해도 되지만, 빈으로 한 번 등록해 두면 구현체마다 생성 코드를 반복하지 않는다.
 * 스프링이 주입하는 EntityManager는 트랜잭션별 실제 EM에 위임하는 프록시라서
 * 싱글턴 빈으로 공유해도 스레드 안전하다.
 */
@Configuration
public class QuerydslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
