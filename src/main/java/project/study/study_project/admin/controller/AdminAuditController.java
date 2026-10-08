package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.audit.AdminAuditItem;
import project.study.study_project.admin.audit.AdminAuditService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;

/** 처리 기록 조회 — 읽기만 있다. 기록은 고치거나 지우는 길을 두지 않는다. */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuditService auditService;

    @GetMapping
    public ApiResponse<PageResponse<AdminAuditItem>> list(@PageableDefault(size = 30) Pageable pageable) {
        return ApiResponse.ok(auditService.list(pageable));
    }
}
