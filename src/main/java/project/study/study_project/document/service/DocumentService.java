package project.study.study_project.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.config.CacheConfig;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.document.support.DocumentEditions;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 문서 조회 서비스 — 목록(필터·페이징)과 단건(slug). API 스펙은 docs/03.
 *
 * <p>목록은 처음에 Specification + 엔티티 조회로 구현했다가 <b>로드맵 1에서 QueryDSL
 * DTO 프로젝션으로 교체</b>했다(태그 N+1 구조 제거 + 본문 미전송 — DocumentRepositoryImpl 주석,
 * 실측 수치는 docs/08). 서비스는 이제 조립 없이 리포지토리에 위임만 한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;

    /**
     * 문서 목록. 도메인·태그 필터는 선택. 본문은 제외한 요약 항목으로 페이지를 만든다.
     *
     * @param domain 도메인 필터(없으면 전체)
     * @param tags   태그명 필터(없으면 전체). 주어지면 <b>그 중 하나라도 달린</b> 문서를 반환(OR).
     */
    @Transactional(readOnly = true)
    public PageResponse<DocumentListItem> getDocuments(Domain domain, List<String> tags, Pageable pageable) {
        return PageResponse.from(withEditions(documentRepository.searchListItems(domain, tags, pageable)));
    }

    /**
     * 이 페이지 문서들에 편(입문/심화) 이름을 붙인다 — <b>짝이 실제로 있는 것만</b>(2026-09-03).
     *
     * <p><b>왜 페이지 안에서만 짝을 찾지 않나.</b> 두 편은 같은 날 만들어지므로 목록에서 대개
     * 나란히 오지만, 페이지 경계에 걸리거나 태그·분야 필터가 한쪽만 걸러 내면 짝이 다른
     * 페이지에 있다. 그러면 <b>같은 문서가 페이지를 넘길 때마다 배지가 붙었다 떨어졌다 한다</b>.
     * 목록 안에서 찾는 것이 공짜라는 이유로 그 흔들림을 받아들일 이유가 없다.
     *
     * <p><b>쿼리는 한 방 늘어난다.</b> 후보 slug를 모아 {@code findExistingSlugs}로 한 번에 묻는다 —
     * 문서마다 존재 확인을 하면 20건짜리 페이지에 쿼리가 20번 나간다(그 메서드가 원래
     * 그 N+1을 막으려고 생겼다). 빈 목록으로 부르면 {@code IN ()}이 되어 문법 오류가 나므로
     * 위에서 먼저 막는다.
     */
    private Page<DocumentListItem> withEditions(Page<DocumentListItem> page) {
        List<String> candidates = page.getContent().stream()
                .map(item -> DocumentEditions.counterpartSlugOf(item.slug()))
                .filter(Objects::nonNull)
                .toList();
        if (candidates.isEmpty()) {
            return page;
        }
        Set<String> existing = new HashSet<>(documentRepository.findExistingSlugs(candidates));
        return page.map(item -> {
            String counterpart = DocumentEditions.counterpartSlugOf(item.slug());
            return counterpart != null && existing.contains(counterpart)
                    ? item.withEdition(DocumentEditions.labelOf(item.slug()))
                    : item;
        });
    }

    /** slug로 문서 단건. 없으면 {@link ErrorCode#DOC_001}(404).
     * 단건은 본문·태그가 전부 필요해서 엔티티 조회 그대로 둔다(open-in-view=false라
     * LAZY 태그 접근은 이 트랜잭션 안에서 끝낸다).
     *
     * <p>{@code @Cacheable}(로드맵 2): 같은 slug 재요청은 DB 대신 Redis에서 응답 DTO를 꺼낸다.
     * 캐싱 대상 선정 이유·TTL·직렬화는 CacheConfig 주석 참고. 무효화는 관리자
     * 수정/삭제(AdminDocumentService)가 담당한다. <b>엔티티가 아니라 DTO를 캐싱</b>하는 이유:
     * 엔티티는 LAZY 프록시·영속성 컨텍스트와 얽혀 직렬화가 위험하고, 캐시에서 꺼낸 뒤의
     * 변경 감지 오동작 여지도 있다 — 응답 완성본(DTO)이 캐시에 안전한 형태다. */
    @Cacheable(cacheNames = CacheConfig.DOCUMENT_CACHE, key = "#slug")
    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(String slug) {
        Document document = documentRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
        return DocumentDetailResponse.withEdition(document, existingCounterpartOf(slug));
    }

    /**
     * 짝이 되는 편이 <b>실제로 있으면</b> 그 slug, 없으면 {@code null}.
     *
     * <p>이 값이 캐시에 함께 들어간다는 점이 중요하다. 심화편이 나중에 승인되면 입문편의
     * 캐시에는 여전히 "짝 없음"이 남으므로, {@code AdminDocumentService}가 문서를
     * 만들거나 지울 때 <b>짝의 캐시도 함께</b> 비운다. 한쪽만 비우면 새로 승인한 심화편에서는
     * 입문편으로 가는 링크가 보이는데 입문편에서는 안 보이는, 이상한 한쪽 통행이 최대 10분간 남는다.
     */
    private String existingCounterpartOf(String slug) {
        String counterpart = DocumentEditions.counterpartSlugOf(slug);
        return counterpart != null && documentRepository.existsBySlug(counterpart) ? counterpart : null;
    }
}
