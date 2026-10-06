package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminSuspendRequest;
import project.study.study_project.admin.dto.AdminUserItem;
import project.study.study_project.admin.service.AdminUserService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;

/**
 * 사용자 찾기와 정지·해제 — {@code /api/admin/**}이라 SecurityConfig의 한 줄이 ADMIN만 들인다.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /** 예: {@code GET /api/admin/users?q=민수&suspendedOnly=true}. 정렬은 서비스가 정한다(최근 가입부터). */
    @GetMapping
    public ApiResponse<PageResponse<AdminUserItem>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean suspendedOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(adminUserService.search(q, suspendedOnly, pageable));
    }

    /** 사용자 한 명 — 상세 화면이 읽는다. 없는 사용자는 404 USER_001. */
    @GetMapping("/{id}")
    public ApiResponse<AdminUserItem> detail(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.detail(id));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<AdminUserItem> suspend(@PathVariable Long id,
                                              @Valid @RequestBody AdminSuspendRequest request) {
        return ApiResponse.ok(adminUserService.suspend(id, request));
    }

    @PostMapping("/{id}/unsuspend")
    public ApiResponse<AdminUserItem> unsuspend(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.unsuspend(id));
    }
}
