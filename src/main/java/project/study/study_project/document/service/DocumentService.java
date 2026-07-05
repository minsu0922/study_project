package project.study.study_project.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.config.CacheConfig;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.repository.DocumentRepository;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.global.response.PageResponse;

import java.util.List;

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
        return PageResponse.from(documentRepository.searchListItems(domain, tags, pageable));
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
        return DocumentDetailResponse.from(document);
    }
}
