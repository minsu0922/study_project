package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminBadgeCounts;
import project.study.study_project.admin.service.AdminBadgeService;
import project.study.study_project.global.response.ApiResponse;

/** 관리 콘솔 메뉴 배지 — 개수 여덟을 한 요청으로(요청 한도 때문이다, {@link AdminBadgeCounts} 주석). */
@RestController
@RequiredArgsConstructor
public class AdminBadgeController {

    private final AdminBadgeService adminBadgeService;

    @GetMapping("/api/admin/badge-counts")
    public ApiResponse<AdminBadgeCounts> counts() {
        return ApiResponse.ok(adminBadgeService.counts());
    }
}
