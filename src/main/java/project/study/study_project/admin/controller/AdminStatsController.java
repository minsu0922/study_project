package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminDashboardResponse;
import project.study.study_project.admin.service.AdminStatsService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 관리자 대시보드 API — 관리 화면 첫 진입 시 한 번 호출해 요약·현황판·정답률을 받는다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    /** 예: {@code GET /api/admin/dashboard} */
    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard() {
        return ApiResponse.ok(adminStatsService.getDashboard());
    }
}
