package project.study.study_project.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 * <p>키 구조: {@code refresh:{token}} → 값: userId. 토큰 자체가 키라 조회가 O(1)이고,
 * 사용자당 여러 기기 로그인(토큰 여러 개)도 자연스럽게 허용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 새 refresh 토큰 발급. UUID 2개를 이어 붙여 추측 불가능한 256비트급 랜덤 값을 만든다.
     *
     * <p>Redis 장애 시 null을 반환한다(예외 전파 안 함) — refresh는 편의 기능이므로
     * "로그인 자체가 안 되는 것"보다 "이번엔 access만 발급되는 것"이 낫다(우아한 성능 저하).
     */
    public String issue(Long userId, Duration validity) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + token, String.valueOf(userId), validity);
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
     */
    public Long consume(String token) {
        String userId = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
        return userId == null ? null : Long.valueOf(userId);
    }

    /** 로그아웃 등 명시적 폐기. 이미 없어도 조용히 성공(멱등). */
    public void revoke(String token) {
        try {
            redisTemplate.delete(KEY_PREFIX + token);
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 refresh 토큰 폐기 실패(TTL 만료에 맡김): {}", e.getMessage());
        }
    }
}
