package project.study.study_project.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import project.study.study_project.user.service.AccountDeleted;
import project.study.study_project.user.service.PasswordChanged;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * refresh 토큰 저장소 — Redis 기반 (로드맵 2, ADR-0001의 예고 이행).
 *
 * <p><b>왜 refresh 토큰은 JWT가 아니라 불투명(opaque) 랜덤 문자열인가</b>:
 * access 토큰(JWT)의 존재 이유는 "서버가 상태를 안 들고 서명만 검증"인데, refresh 토큰의
 * 존재 이유는 정반대로 <b>"서버가 회수(무효화)할 수 있어야 한다"</b>이다. 어차피 서버 저장소
 * (Redis) 조회가 필수라면 자기서술적인 JWT일 이유가 없고, 내용 없는 랜덤 값이 오히려
 * 정보 노출이 없어 안전하다. — "왜 하나는 JWT고 하나는 아닌가"는 단골 면접 질문.
 *
 * <p><b>왜 Redis인가</b>: TTL을 키에 붙이면 만료 청소가 공짜(RDB면 만료 행 배치 삭제 필요),
 * 조회가 메모리 속도, 그리고 인증 상태가 앱 서버 밖에 있으니 서버를 여러 대로 늘려도 공유된다.
 *
 * <p>키 구조: {@code refresh:{token}} → 값: {@code userId:발급시각}. 토큰 자체가 키라 조회가 O(1)이고,
 * 사용자당 여러 기기 로그인(토큰 여러 개)도 자연스럽게 허용된다.
 *
 * <p>사용자의 토큰을 한꺼번에 끊을 때는 토큰을 찾아 지우지 않고 {@code refresh-cutoff:{userId}}에
 * 시각을 적는다. 그 시각 이전에 발급된 토큰은 소비할 때 무효로 본다. access 토큰도 같은 시각을
 * 본다({@link #isCutOff}) — JWT는 회수할 수 없으니 "언제 이전 것은 안 받는다"만 적어 둔다 — 사용자별 토큰 목록을
 * 따로 들고 있으면 발급·소비마다 두 키를 맞춰야 하고, 어긋난 목록은 끊기지 않는 토큰을 남긴다.
 */
@Slf4j
@Component
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";
    private static final String CUTOFF_PREFIX = "refresh-cutoff:";

    private final StringRedisTemplate redisTemplate;
    /** 기준 시각의 수명. 가장 오래 사는 토큰만큼만 남기면 된다 — 그 뒤에는 끊을 토큰이 없다. */
    private final Duration cutoffTtl;

    public RefreshTokenStore(StringRedisTemplate redisTemplate,
                             @Value("${jwt.refresh-token-validity-seconds}") long refreshValiditySeconds) {
        this.redisTemplate = redisTemplate;
        this.cutoffTtl = Duration.ofSeconds(refreshValiditySeconds);
    }

    /**
     * 새 refresh 토큰 발급. UUID 2개를 이어 붙여 추측 불가능한 256비트급 랜덤 값을 만든다.
     *
     * <p>Redis 장애 시 null을 반환한다(예외 전파 안 함) — refresh는 편의 기능이므로
     * "로그인 자체가 안 되는 것"보다 "이번엔 access만 발급되는 것"이 낫다(우아한 성능 저하).
     */
    public String issue(Long userId, Duration validity) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + token,
                    userId + ":" + System.currentTimeMillis(), validity);
            return token;
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 refresh 토큰 발급 생략(access 전용 로그인): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 토큰을 <b>소비</b>한다 — 조회와 동시에 삭제(GETDEL). 성공 시 userId, 무효면 null.
     *
     * <p>조회·삭제를 한 번에 하는 이유(회전, rotation): refresh 토큰은 한 번 쓰면 버리고
     * 새것으로 교체한다. 탈취범과 주인이 같은 토큰을 쓰다가 한쪽이 재발급하는 순간 다른 쪽이
     * 무효가 되므로, 탈취가 "영원한 출입증"이 되지 못한다.
     *
     * <p>Redis 장애 시에도 null을 반환한다(예외 전파 안 함) — {@link #issue}·{@link #revoke}와
     * 같은 원칙이다. 이게 없으면 AuthService.refresh()가 이 예외를 못 잡고 500으로 새 나가는데,
     * 클라이언트 입장에선 "토큰이 무효함(401 AUTH_005, 재로그인)"과 "서버가 지금 확인할 수
     * 없음"을 구분해 봐야 딱히 할 수 있는 게 다르지 않다(둘 다 재로그인 유도) — 그래서 판단이
     * 불확실할 땐 더 단순하고 이미 클라이언트가 처리할 줄 아는 쪽(무효 취급)으로 접는다.
     */
    public Long consume(String token) {
        try {
            String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
            if (value == null) {
                return null;
            }
            // 발급시각이 없는 값은 이 형식이 생기기 전에 발급된 토큰이다. 0으로 보면
            // 기준 시각이 한 번이라도 적힌 사용자에게서는 무효가 된다.
            int colon = value.indexOf(':');
            Long userId = Long.valueOf(colon < 0 ? value : value.substring(0, colon));
            long issuedAt = colon < 0 ? 0L : Long.parseLong(value.substring(colon + 1));
            String cutoff = redisTemplate.opsForValue().get(CUTOFF_PREFIX + userId);
            return cutoff != null && issuedAt <= Long.parseLong(cutoff) ? null : userId;
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 refresh 토큰 검증 불가 — 무효 토큰과 동일하게 취급(재로그인 유도): {}", e.getMessage());
            return null;
        }
    }

    /** 로그아웃 등 명시적 폐기. 이미 없어도 조용히 성공(멱등). */
    public void revoke(String token) {
        try {
            redisTemplate.delete(KEY_PREFIX + token);
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 refresh 토큰 폐기 실패(TTL 만료에 맡김): {}", e.getMessage());
        }
    }

    /**
     * 그 사용자에게 지금까지 발급된 토큰(refresh·access)을 전부 무효로 만든다 — 비밀번호가 바뀌거나
     * 계정이 지워진 순간에 부른다.
     * 비밀번호를 바꾸는 이유가 "누가 내 계정을 쓰는 것 같다"인데 그 사람의 토큰이 14일 더 통하면 안 된다.
     *
     * <p>Redis 장애 시에는 경고만 남긴다. 여기서 예외를 올리면 비밀번호 변경이 통째로 실패하는데,
     * 바뀐 비밀번호라도 남는 쪽이 낫다.
     */
    @EventListener
    public void onPasswordChanged(PasswordChanged event) {
        revokeAll(event.userId());
    }

    @EventListener
    public void onAccountDeleted(AccountDeleted event) {
        revokeAll(event.userId());
    }

    /**
     * 그 시각에 발급된 access 토큰이 끊긴 것인가. 인증 필터가 요청마다 묻는다.
     *
     * <p>JWT의 발급 시각은 초 단위라 초로 견준다. 기준 시각과 같은 초에 발급된 토큰은 받는다 —
     * 비밀번호를 바꾸고 곧바로 다시 로그인한 사람의 새 토큰을 막지 않기 위해서다.
     *
     * <p>Redis 장애 시에는 끊기지 않은 것으로 본다. 여기서 막으면 Redis가 죽은 동안 아무도 로그인 상태로
     * 쓸 수 없다(다른 Redis 의존과 같은 fail-open).
     */
    public boolean isCutOff(Long userId, Instant issuedAt) {
        try {
            String cutoff = redisTemplate.opsForValue().get(CUTOFF_PREFIX + userId);
            return cutoff != null && issuedAt.getEpochSecond() < Long.parseLong(cutoff) / 1000;
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 access 토큰 폐기 여부 확인 생략: userId={} — {}", userId, e.getMessage());
            return false;
        }
    }

    public void revokeAll(Long userId) {
        try {
            redisTemplate.opsForValue().set(CUTOFF_PREFIX + userId,
                    String.valueOf(System.currentTimeMillis()), cutoffTtl);
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 사용자 refresh 토큰 일괄 폐기 실패: userId={} — {}", userId, e.getMessage());
        }
    }
}
