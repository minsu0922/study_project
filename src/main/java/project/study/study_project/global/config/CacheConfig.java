package project.study.study_project.global.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

/**
 * Redis 캐시의 공용 설정 — 연결, 캐시 매니저, 장애 처리(로드맵 2).
 *
 * <p>무엇을 어떻게 캐싱하는지는 여기 없다. 캐시를 쓰는 패키지가
 * {@link RedisCacheManagerBuilderCustomizer} 빈으로 자기 캐시를 얹는다(예: 문서의 DocumentCacheConfig).
 * 여기서 값 타입을 알면 공용 패키지가 기능 패키지에 기댄다.
 */
@Slf4j
@Configuration
@EnableCaching // @Cacheable/@CacheEvict 애너테이션 동작 스위치
public class CacheConfig implements CachingConfigurer {

    /**
     * Redis 연결에 TCP keepalive 적용 — "조용히 식어 죽는 연결" 방지.
     *
     * <p>실측한 문제: Lettuce는 연결 하나를 계속 재사용하는데, 중간 네트워크(WSL NAT 등)가
     * 유휴 연결을 소리 없이 끊으면 다음 명령이 타임아웃까지 매달렸다(처음엔 60초 기본값 →
     * 분 단위 hang). 15초마다 생존 신호를 보내 끊김을 조기에 감지하고 재연결하게 한다.
     * yml의 timeout 2초(빨리 실패)와 함께 이중 방어.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceKeepAlive() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .keepAlive(SocketOptions.KeepAliveOptions.builder()
                                .enable()
                                .idle(Duration.ofSeconds(15))    // 15초 유휴 시 생존 확인 시작
                                .interval(Duration.ofSeconds(5)) // 5초 간격 재시도
                                .count(3)                        // 3회 무응답이면 죽은 연결로 판정
                                .build())
                        .build())
                .build());
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectProvider<RedisCacheManagerBuilderCustomizer> customizers) {
        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /**
     * 캐시 장애를 서비스 장애로 번지지 않게 하는 방화벽.
     * Redis가 죽어도: 읽기 실패 = "캐시 미스"로 취급(→ DB로 조회), 쓰기/삭제 실패 = 무시.
     * 캐시는 어디까지나 <b>성능 보조 장치</b>다 — 보조 장치가 죽었다고 본 기능(문서 조회)이
     * 500을 내면 주객전도. 로그만 남겨 운영자가 알아차리게 한다.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("캐시 조회 실패(미스로 처리) cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("캐시 저장 실패(무시) cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("캐시 무효화 실패(무시) cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("캐시 전체삭제 실패(무시) cache={}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
