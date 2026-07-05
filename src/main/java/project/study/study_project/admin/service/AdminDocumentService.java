package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.config.CacheConfig;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.tag.service.TagService;

/**
 * 관리자 문서 관리 — 등록/수정/삭제.
 *
 * <p>문제와 달리 목록/단건 조회 API를 따로 만들지 않았다: 공개 API(GET /api/documents)가
 * 이미 관리 화면에 필요한 모든 필드를 주기 때문(문서엔 "정답"처럼 숨길 것이 없다).
 * 같은 데이터에 API를 두 벌 만드는 건 유지보수 비용만 늘린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDocumentService {

    private final DocumentRepository documentRepository;
    private final TagService tagService;
    private final CacheManager cacheManager; // 문서 캐시 무효화용 (로드맵 2, CacheConfig 참고)

    /** 문서 등록. slug는 URL 식별자라 중복이면 409(DOC_002). 태그는 find-or-create. */
    @Transactional
    public DocumentDetailResponse create(AdminDocumentRequest request) {
        if (documentRepository.existsBySlug(request.slug())) {
            throw new BusinessException(ErrorCode.DOC_002);
        }
        Document document = Document.create(
                request.domain(), request.title().trim(), request.slug(),
                request.contentMd(), trimOrNull(request.source()),
                tagService.resolveTags(request.tags()));
        return DocumentDetailResponse.from(documentRepository.save(document));
    }

    /**
     * 문서 수정 — 전체 교체 방식. slug를 바꾸는 경우에만 중복 검사(자기 자신과의 충돌 방지).
     *
     * <p>캐시 무효화: 조회 캐시의 키가 slug라서, <b>수정 전 slug와 수정 후 slug 둘 다</b> 지운다.
     * 옛 slug만 지우면 새 slug 캐시가 남을 일은 없지만(아직 캐시된 적 없음), slug가 바뀌는
     * 경우 옛 키를 안 지우면 "옛 주소로 들어가면 옛 내용이 10분간 살아있는" 유령이 남는다.
     * {@code @CacheEvict} 애너테이션 대신 코드로 지우는 이유: 애너테이션은 키를 하나만 지정할 수 있다.
     */
    @Transactional
    public DocumentDetailResponse update(Long id, AdminDocumentRequest request) {
        Document document = findDocument(id);
        String oldSlug = document.getSlug();
        if (!oldSlug.equals(request.slug()) && documentRepository.existsBySlug(request.slug())) {
            throw new BusinessException(ErrorCode.DOC_002);
        }
        document.update(request.domain(), request.title().trim(), request.slug(),
                request.contentMd(), trimOrNull(request.source()),
                tagService.resolveTags(request.tags()));
        evictDocumentCache(oldSlug, request.slug());
        return DocumentDetailResponse.from(document); // 변경 감지로 커밋 시 UPDATE
    }

    /**
     * 문서 삭제. 문제와 달리 제한 없이 지운다 — 문서를 참조하는 제출 이력 같은 자식 데이터가 없고,
     * 태그 연결(document_tag)은 DDL의 ON DELETE CASCADE가 함께 정리한다. 캐시도 함께 무효화.
     */
    @Transactional
    public void delete(Long id) {
        Document document = findDocument(id);
        documentRepository.delete(document);
        evictDocumentCache(document.getSlug());
    }

    /**
     * slug 키들의 문서 캐시를 지운다. Redis 장애로 실패해도 본 작업(수정/삭제)은 성공시킨다.
     *
     * <p><b>직접 try/catch가 필요한 이유(실제 겪은 버그)</b>: CacheConfig의 CacheErrorHandler는
     * {@code @Cacheable/@CacheEvict} <b>애너테이션 경유</b> 작업에만 적용된다. 이렇게 코드로
     * cache.evict()를 부르면 방화벽 밖이라 Redis 타임아웃이 그대로 500으로 터졌다.
     * 무효화가 실패해도 TTL(10분)이 안전망이므로 경고만 남기고 진행한다.
     */
    private void evictDocumentCache(String... slugs) {
        try {
            Cache cache = cacheManager.getCache(CacheConfig.DOCUMENT_CACHE);
            if (cache == null) {
                return;
            }
            for (String slug : slugs) {
                cache.evict(slug);
            }
        } catch (RuntimeException e) {
            log.warn("문서 캐시 무효화 실패(TTL이 안전망) slugs={}: {}", String.join(",", slugs), e.getMessage());
        }
    }

    private Document findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
    }

    private String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
