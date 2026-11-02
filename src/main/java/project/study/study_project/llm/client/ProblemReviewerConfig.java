package project.study.study_project.llm.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 관리 화면 생성에서 쓰는 문제 검수기(REVIEW 2026-11-01 순위 4).
 *
 * <p>부를 때마다 새로 만든다(prototype). {@link ClaudeProblemReviewer}는 토큰 수를 필드에 쌓아서,
 * 하나를 여러 요청이 나눠 쓰면 수가 섞인다. 배치 CLI는 지금처럼 {@code new}로 만든다.
 */
@Configuration
public class ProblemReviewerConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ProblemReviewer problemReviewer(@Value("${llm.generation.model:claude-opus-5}") String model) {
        return new ClaudeProblemReviewer(model);
    }
}
