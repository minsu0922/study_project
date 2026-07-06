package project.study.study_project.global.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 요청 제한 설정값 바인딩 — application.yml의 {@code ratelimit.*}.
 * (이 프로젝트는 설정 주입을 {@code @Value}로 통일하고 있어 그 관례를 따른다 — JwtTokenProvider 등)
 *
 * <p>정책은 딱 2개만 둔다 (docs/09):
 * <ul>
 *   <li><b>auth</b> — 로그인·회원가입·재발급. 비밀번호 무차별 대입(brute force)을 막는 게 목적이라
 *       사람이 손으로 재시도하는 속도(분당 5회)면 충분히 널널하고, 기계 공격에는 치명적이다.
 *   <li><b>api</b> — 그 외 모든 /api/**. 정상 사용을 막지 않으면서 폭주·스크래핑만 걸러내는
 *       널널한 상한(분당 60회). 페이지 진입 시 API 5~6개 동시 호출은 버스트로 흡수된다.
 * </ul>
 * 엔드포인트마다 정책을 붙이는 애너테이션 방식(@RateLimit)도 있지만, 지금은 구분 기준이
 * "인증 경로냐 아니냐" 하나뿐이라 경로 기반 2정책이 가장 단순하다. 필요해지면 그때 확장한다.
 */
@Component
public class RateLimitProperties {

    /** 전체 스위치. 부하 테스트나 로컬 디버깅 때 임시로 끌 수 있게 둔다. */
    private final boolean enabled;
    private final RateLimitPolicy authPolicy;
    private final RateLimitPolicy apiPolicy;

    public RateLimitProperties(
            @Value("${ratelimit.enabled:true}") boolean enabled,
            @Value("${ratelimit.auth.capacity:5}") int authCapacity,
            @Value("${ratelimit.auth.refill-tokens:5}") int authRefillTokens,
            @Value("${ratelimit.auth.refill-period-seconds:60}") int authRefillPeriodSeconds,
            @Value("${ratelimit.api.capacity:60}") int apiCapacity,
            @Value("${ratelimit.api.refill-tokens:60}") int apiRefillTokens,
            @Value("${ratelimit.api.refill-period-seconds:60}") int apiRefillPeriodSeconds
    ) {
        this.enabled = enabled;
        this.authPolicy = new RateLimitPolicy("auth", authCapacity, authRefillTokens, authRefillPeriodSeconds);
        this.apiPolicy = new RateLimitPolicy("api", apiCapacity, apiRefillTokens, apiRefillPeriodSeconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RateLimitPolicy getAuthPolicy() {
        return authPolicy;
    }

    public RateLimitPolicy getApiPolicy() {
        return apiPolicy;
    }
}
