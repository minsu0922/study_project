package project.study.study_project.admin.controller;

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

    /** 등록. slug 중복이면 409(DOC_002). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentDetailResponse> create(@Valid @RequestBody AdminDocumentRequest request) {
        return ApiResponse.ok(adminDocumentService.create(request));
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
