package project.study.study_project.admin.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminRoleRequest;
import project.study.study_project.admin.dto.AdminSuspendRequest;
import project.study.study_project.admin.dto.AdminUserActivity;
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

    /** 그 사람의 활동 — 글·댓글·받은 신고·푼 문제 수와 최근 글, 최근에 받은 신고. */
    @GetMapping("/{id}/activity")
    public ApiResponse<AdminUserActivity> activity(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.activity(id));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<AdminUserItem> suspend(@PathVariable Long id,
                                              @Valid @RequestBody AdminSuspendRequest request) {
        return ApiResponse.ok(adminUserService.suspend(id, request));
    }

    /** 부적절한 닉네임을 "사용자" + 번호로 바꾼다. 본인이 마이페이지에서 다시 정할 수 있다. */
    @PostMapping("/{id}/reset-nickname")
    public ApiResponse<AdminUserItem> resetNickname(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.resetNickname(id));
    }

    /** 권한 변경 `{role}`. 자기 권한은 못 바꾸고(400 USER_003), 정지 중인 사람은 관리자로 못 올린다(409 USER_004). */
    @PostMapping("/{id}/role")
    public ApiResponse<AdminUserItem> changeRole(@AuthenticationPrincipal Long adminId,
                                                 @PathVariable Long id,
                                                 @Valid @RequestBody AdminRoleRequest request) {
        return ApiResponse.ok(adminUserService.changeRole(adminId, id, request.role()));
    }

    @PostMapping("/{id}/unsuspend")
    public ApiResponse<AdminUserItem> unsuspend(@PathVariable Long id) {
        return ApiResponse.ok(adminUserService.unsuspend(id));
    }
}
