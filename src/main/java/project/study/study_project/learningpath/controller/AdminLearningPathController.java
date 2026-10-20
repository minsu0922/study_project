package project.study.study_project.learningpath.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.learningpath.dto.AdminLearningPathItem;
import project.study.study_project.learningpath.dto.AdminLearningPathRequest;
import project.study.study_project.learningpath.dto.AdminPathDocumentOption;
import project.study.study_project.learningpath.service.AdminLearningPathService;

import java.util.List;

/**
 * 관리자 코스(추천 코스) 관리 — 권한은 SecurityConfig의 {@code /api/admin/**}가 막고,
 * 쓰기 요청은 처리 기록 인터셉터가 남긴다(V35).
 */
@RestController
@RequestMapping("/api/admin/learning-paths")
@RequiredArgsConstructor
public class AdminLearningPathController {

    private final AdminLearningPathService service;

    @GetMapping
    public ApiResponse<List<AdminLearningPathItem>> list() {
        return ApiResponse.ok(service.list());
    }

    /** 단계로 고를 수 있는 문서 — 내린 문서도 함께(표시만 다르다) */
    @GetMapping("/documents")
    public ApiResponse<List<AdminPathDocumentOption>> documents() {
        return ApiResponse.ok(service.documentOptions());
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminLearningPathItem> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<AdminLearningPathItem> create(@Valid @RequestBody AdminLearningPathRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminLearningPathItem> update(@PathVariable Long id, @Valid @RequestBody AdminLearningPathRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
