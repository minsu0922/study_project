package project.study.study_project.global.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis Lua 스크립트 기반 토큰 버킷 실행기 — 로드맵 3. 설계는 docs/09, 결정 배경은 ADR-0003.
 *
 * <p><b>왜 카운터를 Redis에 두나</b>: 요청 횟수는 "아주 자주 쓰고(매 요청) 금방 버리는(TTL)"
 * 데이터라 RDB에 두면 오히려 DB가 먼저 병목이 된다. 또 앱 서버가 여러 대로 늘어나도
 * 카운터가 서버 밖(Redis)에 있어야 "사용자당 분당 N회"가 전체 기준으로 지켜진다
 * (서버마다 따로 세면 서버 수만큼 한도가 뻥튀기된다).
 *
 * <p><b>왜 자바가 아니라 Lua에서 판정하나</b>: 읽기→계산→쓰기의 원자성 확보.
 * 상세한 이유는 스크립트({@code scripts/rate-limit.lua}) 상단 주석 참고.
 *
 * <p><b>왜 Bucket4j 같은 라이브러리를 안 썼나</b>: 이 프로젝트의 목적은 원리를 직접 겪는 것.
 * 핵심 로직이 Lua 40줄 정도로 작아 직접 구현·검증이 가능하고, 의존성도 늘지 않는다.
 * 실무 대규모라면 검증된 라이브러리가 합리적 — 트레이드오프는 ADR-0003에 기록.
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    /** 버킷 키 공통 접두어. Redis에서 rl:* 로 요청 제한 키만 골라 볼 수 있게 한다. */
    private static final String KEY_PREFIX = "rl:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Lua 스크립트는 부팅 시 한 번 로드해 재사용한다. Spring이 내부적으로 EVALSHA
     * (스크립트 본문 대신 해시만 전송)를 써서 매 요청마다 스크립트 전문을 보내지 않는다.
     */
    private final RedisScript<List> script;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate-limit.lua")));
        s.setResultType(List.class);
        this.script = s;
    }

    /**
     * 토큰 1개 소비를 시도한다.
     *
     * @param bucketKey 정책 이름 뒤에 붙는 식별자 (예: "ip:1.2.3.4", "user:42")
     * @param policy    적용할 정책 (버킷 크기·충전 속도)
     * @return 통과 여부와, 거부 시 재시도 대기 시간
     */
    @SuppressWarnings("unchecked")
    public RateLimitResult tryConsume(String bucketKey, RateLimitPolicy policy) {
        // 최종 키 예: rl:auth:ip:1.2.3.4 — 정책이 다르면 같은 IP라도 버킷이 분리된다.
        String key = KEY_PREFIX + policy.name() + ":" + bucketKey;
        try {
            List<Long> result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(policy.capacity()),
                    String.valueOf(policy.refillTokens()),
                    String.valueOf(policy.refillPeriodSeconds() * 1000L));
            boolean allowed = result.get(0) == 1L;
            if (!allowed) {
                // 공격 탐지·한도 튜닝의 근거가 되는 로그. 남용 IP/사용자를 여기서 발견한다.
                log.info("요청 제한 발동 key={} retryAfter={}s", key, result.get(1));
            }
            return new RateLimitResult(allowed, result.get(1));
        } catch (DataAccessException e) {
            // fail-open: 제한기 장애가 서비스 장애로 번지지 않게 한다(RateLimitResult.failOpen 참고).
            log.warn("Redis 장애로 요청 제한 생략(fail-open) key={}: {}", key, e.getMessage());
            return RateLimitResult.failOpen();
        }
    }
}
