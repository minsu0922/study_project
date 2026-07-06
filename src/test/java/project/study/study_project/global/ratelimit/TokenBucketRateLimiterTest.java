package project.study.study_project.global.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 토큰 버킷 Lua 스크립트의 실동작 검증 — 실제 Redis에 붙어서 확인한다.
 *
 * <p><b>왜 실제 Redis로 테스트하나</b>: 판정 로직이 자바가 아니라 Lua 스크립트 안에 있어서
 * 자바만 mocking해서는 정작 핵심(원자적 판정·충전 계산)을 하나도 검증하지 못한다.
 * 스프링 컨텍스트 없이 Lettuce 연결만 직접 만들어 가볍게 돌린다(부팅 몇 초 절약).
 *
 * <p>Redis가 안 떠 있으면 실패가 아니라 <b>건너뛴다</b>(Assumption) — 로컬 인프라(WSL redis)의
 * 부재를 코드 결함처럼 빨간불로 보고하면 신호가 오염되기 때문. 대신 건너뛴 이유를 메시지로 남긴다.
 * WSL의 localhost 포트 중계가 죽어 있으면 {@code REDIS_HOST}에 WSL IP를 넣고 돌린다
 * (예: {@code REDIS_HOST=$(wsl hostname -I) gradlew test} — application.yml 주석 참고).
 *
 * <p>키는 매 테스트 랜덤 UUID — 이전 실행이 남긴 버킷과 절대 안 겹치게(테스트 독립성).
 */
class TokenBucketRateLimiterTest {

    private static LettuceConnectionFactory connectionFactory;
    private static TokenBucketRateLimiter limiter;

    @BeforeAll
    static void setUp() {
        // 앱과 동일하게 REDIS_HOST 환경변수를 존중한다(기본 127.0.0.1 — application.yml 주석 참고).
        String host = System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1");
        connectionFactory = new LettuceConnectionFactory(host, 6379);
        connectionFactory.afterPropertiesSet();
        // Spring Data Redis 3.x: 팩토리가 SmartLifecycle이 되면서 스프링 컨테이너 밖에서 쓰려면
        // start()를 직접 불러야 한다(안 부르면 연결 시도 자체가 예외).
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        // Redis 응답 확인 — 안 떠 있으면 이 클래스 전체 skip (이유는 메시지에 남긴다).
        boolean redisUp;
        String reason = "";
        try {
            redisUp = "PONG".equals(template.execute(
                    connection -> connection.ping(), true));
        } catch (Exception e) {
            redisUp = false;
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        assumeTrue(redisUp, "Redis(" + host + ":6379) 미가동 — 토큰 버킷 실동작 테스트 건너뜀. " + reason);

        limiter = new TokenBucketRateLimiter(template);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static String randomKey() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("capacity만큼 버스트를 허용하고, 그 다음 요청은 거부한다")
    void burstUpToCapacityThenDeny() {
        RateLimitPolicy policy = new RateLimitPolicy("t-burst", 5, 5, 60);
        String key = randomKey();

        // 처음 5번(=capacity)은 전부 통과해야 한다 — 페이지 진입 시 동시 호출을 막지 않기 위한 설계.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(key, policy).allowed())
                    .as("%d번째 요청은 버스트 허용량 안", i + 1)
                    .isTrue();
        }

        // 6번째는 거부 + "언제 다시 올지"가 양수로 내려와야 한다.
        RateLimitResult denied = limiter.tryConsume(key, policy);
        assertThat(denied.allowed()).isFalse();
        // 분당 5개 충전 = 토큰 1개당 12초. 방금 소진 직후이니 1~12초 사이여야 한다.
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 12L);
    }

    @Test
    @DisplayName("버킷 키가 다르면 서로의 소비에 영향을 주지 않는다 (사용자별 독립)")
    void bucketsAreIndependent() {
        RateLimitPolicy policy = new RateLimitPolicy("t-indep", 1, 1, 60);
        String keyA = randomKey();
        String keyB = randomKey();

        assertThat(limiter.tryConsume(keyA, policy).allowed()).isTrue();
        assertThat(limiter.tryConsume(keyA, policy).allowed()).isFalse(); // A는 소진
        // A가 소진돼도 B는 새 버킷 — "한 명의 폭주가 다른 사용자를 막으면 안 된다"의 검증.
        assertThat(limiter.tryConsume(keyB, policy).allowed()).isTrue();
    }

    @Test
    @DisplayName("시간이 지나면 토큰이 다시 채워진다 (lazy refill)")
    void refillsOverTime() throws InterruptedException {
        // 빨리 검증하려고 충전 주기를 1초로 줄인 정책 사용 (capacity 2, 초당 2개 충전).
        RateLimitPolicy policy = new RateLimitPolicy("t-refill", 2, 2, 1);
        String key = randomKey();

        assertThat(limiter.tryConsume(key, policy).allowed()).isTrue();
        assertThat(limiter.tryConsume(key, policy).allowed()).isTrue();
        assertThat(limiter.tryConsume(key, policy).allowed()).isFalse(); // 소진

        // 1초(=완전 충전 주기)를 기다리면 다시 통과해야 한다.
        Thread.sleep(1100);
        assertThat(limiter.tryConsume(key, policy).allowed()).isTrue();
    }

    @Test
    @DisplayName("같은 키라도 정책 이름이 다르면 별도 버킷을 쓴다")
    void policiesDoNotShareBuckets() {
        String key = randomKey();
        RateLimitPolicy strict = new RateLimitPolicy("t-strict", 1, 1, 60);
        RateLimitPolicy loose = new RateLimitPolicy("t-loose", 10, 10, 60);

        assertThat(limiter.tryConsume(key, strict).allowed()).isTrue();
        assertThat(limiter.tryConsume(key, strict).allowed()).isFalse(); // strict 소진
        // 같은 IP가 auth 한도를 다 써도 일반 api 호출은 살아 있어야 한다 — 그 성질의 축소판.
        assertThat(limiter.tryConsume(key, loose).allowed()).isTrue();
    }
}
