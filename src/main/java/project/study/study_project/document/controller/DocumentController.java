package project.study.study_project.document.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.document.dto.DocumentDetailResponse;
import project.study.study_project.document.dto.DocumentListItem;
import project.study.study_project.document.service.DocumentService;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;

import java.util.List;

/**
 * 문서 조회 API — 목록/단건. 명세는 docs/03-api-spec. 둘 다 인증 없이 공개(SecurityConfig).
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 문서 목록. 예: {@code GET /api/documents?domain=NETWORK&tag=tcp&page=0&size=20&sort=createdAt,desc}
     *
     * <p>{@link PageableDefault}로 기본값(size 20, createdAt 내림차순)을 준다. size 상한(100)은
     * {@code application.yml}의 {@code spring.data.web.pageable.max-page-size}로 강제한다.
     * {@code tag} 파라미터는 반복 가능({@code ?tag=a&tag=b})해서 {@code List<String>}로 받는다.
     */
    @GetMapping
    public ApiResponse<PageResponse<DocumentListItem>> list(
            @RequestParam(required = false) Domain domain,
            @RequestParam(required = false, name = "tag") List<String> tags,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(documentService.getDocuments(domain, tags, pageable));
    }

    /** 문서 단건(본문 포함). 예: {@code GET /api/documents/osi-7-layer} */
    @GetMapping("/{slug}")
    public ApiResponse<DocumentDetailResponse> detail(@PathVariable String slug) {
        return ApiResponse.ok(documentService.getDocument(slug));
    }
}
