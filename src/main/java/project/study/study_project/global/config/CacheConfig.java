package project.study.study_project.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import project.study.study_project.document.dto.DocumentDetailResponse;

import java.time.Duration;

/**
 * Redis 캐시 설정 — 로드맵 2. 캐싱 대상과 이유:
 *
 * <p><b>왜 문서 단건(document)만 캐싱하나</b>:
 * <ul>
 *   <li>문서 읽기는 "많이 읽고 거의 안 바뀌는" 캐싱의 교과서적 대상. 바뀌는 경로도
 *       관리자 수정/삭제 딱 두 곳이라 무효화 지점이 명확하다.
 *   <li>퀴즈 조회는 무작위(매번 달라야 함), 오답노트는 개인화+제출마다 변함,
 *       목록은 (도메인×태그×페이지) 조합만큼 키가 불어나 적중률이 낮다 — 전부 캐싱 부적합.
 * </ul>
 *
 * <p><b>TTL 10분 + 무효화 병행</b>: 무효화(evict)가 정상 경로지만, 버그·수동 DB 수정 등으로
 * 무효화가 누락돼도 TTL이 "최대 10분 뒤엔 맞는 값"을 보장하는 안전망이 된다.
 * (TTL 없는 캐시는 한 번 어긋나면 영원히 어긋난다)
 *
 * <p><b>직렬화</b>: 값은 JSON(Jackson). 캐시가 "document" 하나뿐이라 값 타입을
 * {@link DocumentDetailResponse}로 못박은 타입 지정 직렬화기를 쓴다 — 제네릭 직렬화기의
 * 기본 타입정보(@class) 방식은 record(final 클래스)에서 타입 표기가 빠져 역직렬화가
 * LinkedHashMap으로 깨지는 함정이 있다. LocalDateTime 때문에 JavaTimeModule 등록 필수.
 */
@Slf4j
@Configuration
@EnableCaching // @Cacheable/@CacheEvict 애너테이션 동작 스위치
public class CacheConfig implements CachingConfigurer {

    public static final String DOCUMENT_CACHE = "document";

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
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())                       // LocalDateTime 직렬화
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);   // 배열 대신 ISO 문자열로

        RedisCacheConfiguration documentCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(om, DocumentDetailResponse.class)));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(DOCUMENT_CACHE, documentCache)
                .build();
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
