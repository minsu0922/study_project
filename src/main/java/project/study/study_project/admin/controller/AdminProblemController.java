package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminProblemDetail;
import project.study.study_project.admin.dto.AdminProblemRequest;
import project.study.study_project.admin.service.AdminProblemService;
import project.study.study_project.global.common.Domain;
import project.study.study_project.global.common.ProblemType;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;

/**
 * 관리자 문제 관리 API — /api/admin/** 전체가 SecurityConfig에서 hasRole(ADMIN)로 잠긴다.
 * 컨트롤러에는 권한 코드가 한 줄도 없다는 점이 포인트: 경로 규칙 한 곳(SecurityConfig)에서
 * 일괄 통제하므로, 여기에 API를 추가해도 권한을 빼먹을 수 없다.
 */
@RestController
@RequestMapping("/api/admin/problems")
@RequiredArgsConstructor
public class AdminProblemController {

    private final AdminProblemService adminProblemService;

    /** 목록(관리 화면용, 정답 포함). 예: {@code GET /api/admin/problems?domain=NETWORK&page=0} */
    @GetMapping
    public ApiResponse<PageResponse<AdminProblemDetail>> list(
            @RequestParam(required = false) Domain domain,
            @RequestParam(required = false) ProblemType type,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(adminProblemService.getProblems(domain, type, pageable));
    }

    /** 단건(수정 폼 채우기용). */
    @GetMapping("/{id}")
    public ApiResponse<AdminProblemDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(adminProblemService.getProblem(id));
    }

    /** 등록. 성공 시 201 + 생성된 문제 상세(id 포함). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminProblemDetail> create(@Valid @RequestBody AdminProblemRequest request) {
        return ApiResponse.ok(adminProblemService.create(request));
    }

    /** 수정(전체 교체 방식 — 서비스 주석 참고). */
    @PutMapping("/{id}")
    public ApiResponse<AdminProblemDetail> update(@PathVariable Long id,
                                                  @Valid @RequestBody AdminProblemRequest request) {
        return ApiResponse.ok(adminProblemService.update(id, request));
    }

    /** 삭제. 제출 이력이 있으면 409(QUIZ_003). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        adminProblemService.delete(id);
        return ApiResponse.ok();
    }
}
