package project.study.study_project.global.ratelimit;

/**
 * 요청 제한 정책 하나 — "버킷이 얼마나 크고, 얼마나 빨리 다시 차는가". 설계는 docs/09.
 *
 * <p>토큰 버킷의 두 축:
 * <ul>
 *   <li>{@code capacity} = 순간적으로 몰아 쓸 수 있는 최대치(버스트 허용량).
 *       페이지 하나가 API를 5~6개 동시에 부르는 정상 패턴을 막지 않기 위해 필요하다.
 *   <li>{@code refillTokens}/{@code refillPeriodSeconds} = 장기 평균 속도.
 *       "60초당 60개"처럼 사람이 읽기 쉬운 단위로 표현한다(초당 소수점 대신).
 * </ul>
 *
 * @param name                버킷 키 접두어 겸 로그 식별자 (예: "auth", "api")
 * @param capacity            버킷 최대 토큰 수
 * @param refillTokens        충전 주기당 채워지는 토큰 수
 * @param refillPeriodSeconds 충전 주기(초)
 */
public record RateLimitPolicy(String name, int capacity, int refillTokens, int refillPeriodSeconds) {

    /** 로그인·가입·재발급·비밀번호 재설정에 거는 정책의 이름. */
    public static final String AUTH = "auth";

    /**
     * Redis가 죽었을 때도 이 서버 안에서 세어 막을 정책인가. 자격 증명을 맞혀 볼 수 있는 경로만 그렇다 —
     * 나머지는 제한이 풀려도 잃는 것이 서버 자원뿐이라 통과시킨다(fail-open).
     */
    public boolean limitsLocallyOnFailure() {
        return AUTH.equals(name);
    }
}
