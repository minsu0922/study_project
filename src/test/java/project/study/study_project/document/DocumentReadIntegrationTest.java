package project.study.study_project.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.TestDomains;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.config.CacheConfig;
import project.study.study_project.global.exception.BusinessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 문서 <b>읽기</b> 경로 — 독자가 실제로 보는 길(2026-09-16 신설).
 *
 * <h2>왜 이제야 생겼나</h2>
 *
 * <p>통합 테스트 22개 가운데 {@code DocumentService}를 지나는 것이 <b>하나도 없었다</b>.
 * 문서를 만들고 흡수하고 승인하는 길(llm 패키지)은 두껍게 덮여 있는데, 정작 <b>승인된 문서를
 * 독자가 읽는 길</b>은 비어 있었다. 만드는 쪽이 재미있어서 생긴 공백이다.
 *
 * <p>그 공백에서 실제로 사고가 났다(2026-09-16). Flyway 심화편을 손으로 고치며 DB에 직접
 * {@code UPDATE}를 했더니, 앱은 <b>10분 동안 옛 본문을 계속 내보냈다</b>. {@code @Cacheable}이
 * slug를 키로 응답 DTO를 Redis에 들고 있었기 때문이다. DB만 보고 "고쳤다"고 했으면 그대로
 * 넘어갔을 일이다.
 *
 * <h2>이 테스트가 헛돌지 않게 하는 장치</h2>
 *
 * <p><b>캐시가 켜져 있다는 것을 먼저 증명한다.</b> {@code CacheConfig}의 {@code CacheErrorHandler}는
 * Redis가 죽어도 {@code @Cacheable}을 조용히 통과시킨다(운영에서는 옳은 선택이다 — 캐시 때문에
 * 서비스가 죽으면 안 되므로). 그런데 그러면 <b>캐시가 없는 상태에서도 무효화 테스트가
 * 통과한다</b>. 캐시가 없으면 매번 DB를 읽으니 수정이 항상 보이기 때문이다.
 * 초록불인데 아무것도 안 재는, 이 저장소가 가장 경계하는 종류의 통과다.
 *
 * <p>그래서 조회 직후 <b>캐시에 값이 들어갔는지</b>를 먼저 확인한다. Redis가 없으면 그 단언이
 * 먼저 깨지므로, "캐시가 안 도는 환경"과 "무효화가 깨진 것"이 구별된다.
 *
 * <h2>slug를 매번 새로 짓는 이유</h2>
 *
 * <p>클래스 {@code @Transactional}은 <b>DB만</b> 되돌린다. Redis에 남은 캐시는 롤백되지 않아
 * 다음 실행까지 살아 있다. 고정 slug를 쓰면 두 번째 실행이 첫 번째가 남긴 캐시를 읽는다.
 */
@SpringBootTest
@Transactional
class DocumentReadIntegrationTest {

    @Autowired
    private DocumentService documentService;
    @Autowired
    private AdminDocumentService adminDocumentService;
    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("slug로 문서를 읽으면 본문과 태그가 나온다")
    void readsDocumentBySlug() {
        String slug = newSlug();
        adminDocumentService.create(request(slug, "본문이다.", "cache"));

        DocumentDetailResponse found = documentService.getDocument(slug);

        assertThat(found.slug()).isEqualTo(slug);
        assertThat(found.contentMd()).isEqualTo("본문이다.");
        assertThat(found.tags()).contains("cache");
    }

    @Test
    @DisplayName("없는 slug는 404로 막는다")
    void missingSlugIsRejected() {
        assertThatThrownBy(() -> documentService.getDocument(newSlug()))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * <b>오늘 실제로 겪은 사고를 그대로 재현한다.</b> 고치고 다시 읽었을 때 옛 본문이 나오면
     * 독자는 수정된 줄 모르고 옛 설명을 읽는다. 오류도 경고도 없어서 <b>사람이 직접 화면을
     * 열어 보기 전까지 아무도 모른다</b>.
     */
    @Test
    @DisplayName("문서를 고치면 다음 조회에 새 본문이 나온다 — 캐시가 옛 본문을 붙들면 안 된다")
    void updateIsVisibleOnNextRead() {
        String slug = newSlug();
        Long id = adminDocumentService.create(request(slug, "옛 본문이다.", "cache")).id();

        // 먼저 한 번 읽어 캐시에 올린다. 이걸 안 하면 무효화할 대상이 없어 검사가 헛돈다.
        assertThat(documentService.getDocument(slug).contentMd()).isEqualTo("옛 본문이다.");
        assertThat(cachedEntryOf(slug))
                .as("조회 뒤에도 캐시가 비어 있다면 캐시가 안 도는 환경이다 — "
                        + "그 상태에서는 아래 단언이 통과해도 무효화를 증명하지 못한다")
                .isNotNull();

        adminDocumentService.update(id, request(slug, "새 본문이다.", "cache"));

        assertThat(documentService.getDocument(slug).contentMd())
                .as("수정 뒤에도 옛 본문이 나오면 독자는 고쳐진 줄 모르고 옛 설명을 읽는다")
                .isEqualTo("새 본문이다.");
    }

    /**
     * <b>짝 링크의 한쪽 통행을 막는다.</b> 조회 응답에는 "짝이 되는 편이 있는가"가 함께 담기고,
     * 그 값도 캐시에 들어간다. 입문편을 먼저 읽어 캐시에 "짝 없음"이 굳은 뒤 심화편이 승인되면,
     * 심화편에서는 입문편 링크가 보이는데 입문편에서는 안 보이는 상태가 된다.
     *
     * <p>{@code AdminDocumentService}가 문서를 만들 때 <b>짝의 캐시까지</b> 비우는 것이 그 대비다.
     * 조용한 종류라 화면을 두 쪽 다 열어 봐야 드러나므로 여기서 못 박는다.
     */
    @Test
    @DisplayName("심화편을 새로 만들면 입문편에서도 짝 링크가 보인다 — 한쪽만 보이면 안 된다")
    void approvingCounterpartRefreshesTheOtherEdition() {
        String beginner = newSlug();
        adminDocumentService.create(request(beginner, "입문편이다.", "cache"));

        assertThat(documentService.getDocument(beginner).counterpartSlug())
                .as("아직 심화편이 없으니 짝은 없다")
                .isNull();

        adminDocumentService.create(request(beginner + "-advanced", "심화편이다.", "cache"));

        assertThat(documentService.getDocument(beginner).counterpartSlug())
                .as("입문편 캐시에 '짝 없음'이 남으면 링크가 한쪽에서만 보인다")
                .isEqualTo(beginner + "-advanced");
    }

    /* ── 도우미 ─────────────────────────────────────────────── */

    /** 캐시에 실제로 올라간 값. Redis가 없거나 캐시가 꺼져 있으면 {@code null}. */
    private Object cachedEntryOf(String slug) {
        Cache cache = cacheManager.getCache(CacheConfig.DOCUMENT_CACHE);
        if (cache == null) {
            return null;
        }
        Cache.ValueWrapper wrapper = cache.get(slug);
        return wrapper == null ? null : wrapper.get();
    }

    /** 실행마다 새 slug — 캐시는 롤백되지 않으므로 고정 slug를 쓰면 다음 실행이 옛 캐시를 읽는다. */
    private String newSlug() {
        return "read-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private AdminDocumentRequest request(String slug, String contentMd, String tag) {
        return new AdminDocumentRequest(TestDomains.DATABASE, "읽기 경로 테스트 문서", slug,
                contentMd, null, List.of(tag));
    }
}
