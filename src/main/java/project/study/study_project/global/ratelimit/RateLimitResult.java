package project.study.study_project.global.ratelimit;

/**
 * 토큰 버킷 판정 결과.
 *
 * @param allowed           이번 요청을 통과시킬지
 * @param retryAfterSeconds 거부됐을 때 몇 초 뒤 재시도 가능한지 (Retry-After 헤더 값, 허용 시 0)
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    /**
     * Redis 장애 시의 fail-open 결과 — 무조건 허용.
     * <p>제한 장치가 죽었다고 서비스 전체를 막는 것(fail-close)은 주객전도다.
     * 캐시({@code CacheConfig})·refresh 토큰({@code RefreshTokenStore})과 같은 철학:
     * 보조 장치의 장애는 본 기능을 침범하지 않는다. 단, 그 동안 무제한이 되는 위험은
     * 로그로 남겨 운영자가 알게 한다.
     */
    public static RateLimitResult failOpen() {
        return new RateLimitResult(true, 0);
    }
}
