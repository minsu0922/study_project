package project.study.study_project.admin.controller;

import project.study.study_project.admin.audit.AdminAuditInterceptor;
import project.study.study_project.admin.revision.RevisionItem;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.global.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminDocumentRequest;
import project.study.study_project.admin.service.AdminDocumentService;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.global.response.ApiResponse;

/**
 * 관리자 문서 관리 API — 등록/수정/삭제만 있다.
 * 조회는 공개 API(GET /api/documents)를 그대로 쓴다(서비스 주석 참고).
 */
@RestController
@RequestMapping("/api/admin/documents")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final AdminDocumentService adminDocumentService;

    /** 목록 — 내려 둔 문서 포함. 최신 등록 순. */
    @GetMapping
    public ApiResponse<PageResponse<DocumentListItem>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(adminDocumentService.list(pageable));
    }

    /** 단건(수정 폼·본문 복사용). */
    @GetMapping("/{id}")
    public ApiResponse<DocumentDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(adminDocumentService.get(id));
    }

    /** 수정 이력 — 고치기 직전 모습들을 최근 것부터. */
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<RevisionItem>> revisions(@PathVariable Long id) {
        return ApiResponse.ok(adminDocumentService.revisions(id));
    }

    /** 그 모습으로 되돌린다. 지금 모습은 이력에 남는다. */
    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public ApiResponse<DocumentDetailResponse> restoreRevision(@PathVariable Long id,
                                                               @PathVariable Long revisionId) {
        return ApiResponse.ok(adminDocumentService.restore(id, revisionId));
    }

    /** 내리기 — 목록·단건·근거 링크에서 빠진다. */
    @PostMapping("/{id}/hide")
    public ApiResponse<Void> hide(@PathVariable Long id) {
        adminDocumentService.setHidden(id, true);
        return ApiResponse.ok();
    }

    /** 다시 올리기. */
    @PostMapping("/{id}/show")
    public ApiResponse<Void> show(@PathVariable Long id) {
        adminDocumentService.setHidden(id, false);
        return ApiResponse.ok();
    }

    /** 등록. slug 중복이면 409(DOC_002). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentDetailResponse> create(@Valid @RequestBody AdminDocumentRequest request) {
        DocumentDetailResponse created = adminDocumentService.create(request);
        AdminAuditInterceptor.markCreated(created.id());   // 처리 기록에 새 번호를 남긴다(V37)
        return ApiResponse.ok(created);
    }

    /** 수정(전체 교체 방식). */
    @PutMapping("/{id}")
    public ApiResponse<DocumentDetailResponse> update(@PathVariable Long id,
                                                      @Valid @RequestBody AdminDocumentRequest request) {
        return ApiResponse.ok(adminDocumentService.update(id, request));
    }

    /** 삭제. 태그 연결은 DB CASCADE로 함께 정리된다. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminDocumentService.delete(id);
        return ApiResponse.ok();
    }
}
