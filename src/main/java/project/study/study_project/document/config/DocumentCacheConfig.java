package project.study.study_project.document.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import project.study.study_project.document.dto.DocumentDetailResponse;

import java.time.Duration;

/**
 * 문서 단건 캐시 — 로드맵 2. 연결·장애 처리는 공용 CacheConfig에 있다.
 *
 * <p><b>왜 문서 단건만 캐싱하나</b>:
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
 * <p><b>직렬화</b>: 값 타입을 {@link DocumentDetailResponse}로 못박은 직렬화기를 쓴다 — 제네릭
 * 직렬화기의 기본 타입정보(@class) 방식은 record(final 클래스)에서 타입 표기가 빠져 역직렬화가
 * LinkedHashMap으로 깨지는 함정이 있다. LocalDateTime 때문에 JavaTimeModule 등록 필수.
 */
@Configuration
public class DocumentCacheConfig {

    public static final String DOCUMENT_CACHE = "document";

    @Bean
    public RedisCacheManagerBuilderCustomizer documentCacheCustomizer() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())                       // LocalDateTime 직렬화
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);   // 배열 대신 ISO 문자열로

        RedisCacheConfiguration documentCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(om, DocumentDetailResponse.class)));

        return builder -> builder.withCacheConfiguration(DOCUMENT_CACHE, documentCache);
    }
}
