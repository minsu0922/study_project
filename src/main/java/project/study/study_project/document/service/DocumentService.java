package project.study.study_project.document.service;

import jakarta.persistence.criteria.Join;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * <p>모든 메서드는 {@code @Transactional(readOnly = true)}. 이유:
 * open-in-view=false라 지연 로딩(태그)은 트랜잭션 안에서만 가능하므로, DTO 변환(태그 접근)을
 * 서비스 트랜잭션 경계 안에서 끝낸 뒤 컨트롤러로 넘긴다.
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
        Specification<Document> spec = buildSpec(domain, tags);
        Page<Document> page = documentRepository.findAll(spec, pageable);
        // Page.map은 각 요소를 즉시 변환한다 → 트랜잭션 안에서 태그가 로딩됨
        return PageResponse.from(page.map(DocumentListItem::from));
    }

    /** slug로 문서 단건. 없으면 {@link ErrorCode#DOC_001}(404). */
    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(String slug) {
        Document document = documentRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
        return DocumentDetailResponse.from(document);
    }

    /**
     * 있는 조건만 AND로 엮어 동적 쿼리를 만든다.
     * <ul>
     *   <li>domain: 같은 도메인만
     *   <li>tags: 태그 조인 후 {@code name in (...)}. 한 문서가 여러 태그에 걸릴 수 있어 중복이 생기므로
     *       {@code distinct}로 제거한다.
     * </ul>
     */
    private Specification<Document> buildSpec(Domain domain, List<String> tags) {
        Specification<Document> spec = Specification.where(null);

        if (domain != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("domain"), domain));
        }
        if (tags != null && !tags.isEmpty()) {
            spec = spec.and((root, query, cb) -> {
                Join<Object, Object> tagJoin = root.join("tags"); // document_tag를 통한 inner join
                query.distinct(true);
                return tagJoin.get("name").in(tags);
            });
        }
        return spec;
    }
}
