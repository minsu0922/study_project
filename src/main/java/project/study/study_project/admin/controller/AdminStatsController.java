package project.study.study_project.admin.controller;

import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import project.study.study_project.admin.dto.AdminUsageDay;
import project.study.study_project.admin.service.AdminUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminDashboardResponse;
import project.study.study_project.admin.service.AdminStatsService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.admin.dto.AdminProblemStatItem;
import project.study.study_project.admin.dto.AdminUsageSummary;
import project.study.study_project.admin.service.AdminProblemStatsService;
import project.study.study_project.global.common.Difficulty;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.common.ProblemType;

/**
 * 관리자 대시보드 API — 관리 화면 첫 진입 시 한 번 호출해 요약·현황판·정답률을 받는다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;
    private final AdminUsageService adminUsageService;
    private final AdminProblemStatsService adminProblemStatsService;

    /** 예: {@code GET /api/admin/dashboard} */
    @GetMapping("/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard() {
        return ApiResponse.ok(adminStatsService.getDashboard());
    }

    /** 날짜별 가입·푼 사람·제출. 예: {@code GET /api/admin/usage-trend?days=30} */
    @GetMapping("/usage-trend")
    public ApiResponse<List<AdminUsageDay>> usageTrend(
            @RequestParam(defaultValue = "" + AdminUsageService.DEFAULT_DAYS) int days) {
        return ApiResponse.ok(adminUsageService.trend(days));
    }

    /** 고른 기간과 직전 같은 길이 기간의 합계. 예: {@code GET /api/admin/usage-summary?days=30} */
    @GetMapping("/usage-summary")
    public ApiResponse<AdminUsageSummary> usageSummary(
            @RequestParam(defaultValue = "" + AdminUsageService.DEFAULT_DAYS) int days) {
        return ApiResponse.ok(adminUsageService.summary(days));
    }

    /** 문제별 정답률. 예: {@code GET /api/admin/problem-stats?band=LOW&minAttempts=5&sort=RATE_ASC&page=0} */
    @GetMapping("/problem-stats")
    public ApiResponse<PageResponse<AdminProblemStatItem>> problemStats(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DomainCode domain,
            @RequestParam(required = false) Difficulty difficulty,
            @RequestParam(required = false) ProblemType type,
            @RequestParam(required = false) AdminProblemStatsService.RateBand band,
            @RequestParam(required = false) Integer minAttempts,
            @RequestParam(required = false) Boolean hidden,
            @RequestParam(defaultValue = "RATE_ASC") AdminProblemStatsService.Sort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new AdminProblemStatsService.Filter(q, domain, difficulty, type, band, minAttempts, hidden, sort);
        return ApiResponse.ok(adminProblemStatsService.search(filter, page, size));
    }
}
