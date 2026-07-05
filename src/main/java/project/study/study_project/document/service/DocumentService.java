package project.study.study_project.document.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
     * LAZY 태그 접근은 이 트랜잭션 안에서 끝낸다). */
    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(String slug) {
        Document document = documentRepository.findBySlug(slug)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
        return DocumentDetailResponse.from(document);
    }
}
