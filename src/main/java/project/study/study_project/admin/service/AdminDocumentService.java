package project.study.study_project.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.document.domain.Document;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.repository.DocumentRepository;
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
@Service
@RequiredArgsConstructor
public class AdminDocumentService {

    private final DocumentRepository documentRepository;
    private final TagService tagService;

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

    /** 문서 수정 — 전체 교체 방식. slug를 바꾸는 경우에만 중복 검사(자기 자신과의 충돌 방지). */
    @Transactional
    public DocumentDetailResponse update(Long id, AdminDocumentRequest request) {
        Document document = findDocument(id);
        if (!document.getSlug().equals(request.slug()) && documentRepository.existsBySlug(request.slug())) {
            throw new BusinessException(ErrorCode.DOC_002);
        }
        document.update(request.domain(), request.title().trim(), request.slug(),
                request.contentMd(), trimOrNull(request.source()),
                tagService.resolveTags(request.tags()));
        return DocumentDetailResponse.from(document); // 변경 감지로 커밋 시 UPDATE
    }

    /**
     * 문서 삭제. 문제와 달리 제한 없이 지운다 — 문서를 참조하는 제출 이력 같은 자식 데이터가 없고,
     * 태그 연결(document_tag)은 DDL의 ON DELETE CASCADE가 함께 정리한다.
     */
    @Transactional
    public void delete(Long id) {
        documentRepository.delete(findDocument(id));
    }

    private Document findDocument(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_001));
    }

    private String trimOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
