package project.study.study_project.global.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Redis가 죽었을 때의 판정 — 인증 정책은 이 서버 안에서 세어 막고, 나머지는 통과시킨다. */
class TokenBucketRateLimiterFallbackTest {

    private static final RateLimitPolicy AUTH = new RateLimitPolicy(RateLimitPolicy.AUTH, 5, 5, 60);
    private static final RateLimitPolicy API = new RateLimitPolicy("api", 5, 5, 60);

    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        // 무엇을 부르든 연결 실패로 답하는 Redis
        StringRedisTemplate deadRedis = mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("down");
        });
        limiter = new TokenBucketRateLimiter(deadRedis);
    }

    @Test
    @DisplayName("Redis가 죽어도 인증 정책은 한도까지만 통과시킨다")
    void authPolicyIsStillLimited() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("ip:1.2.3.4", AUTH).allowed()).isTrue();
        }
        RateLimitResult sixth = limiter.tryConsume("ip:1.2.3.4", AUTH);

        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfterSeconds()).isBetween(1L, 12L);
    }

    @Test
    @DisplayName("버킷은 키마다 따로다 — 한 IP가 한도를 다 써도 다른 IP는 통과한다")
    void bucketsAreSeparatePerKey() {
        for (int i = 0; i < 6; i++) {
            limiter.tryConsume("ip:1.2.3.4", AUTH);
        }

        assertThat(limiter.tryConsume("ip:5.6.7.8", AUTH).allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis가 죽으면 일반 정책은 제한 없이 통과시킨다")
    void otherPoliciesFailOpen() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryConsume("ip:1.2.3.4", API).allowed()).isTrue();
        }
    }
}
