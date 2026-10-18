package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminQualityResponse;
import project.study.study_project.admin.service.AdminQualityService;
import project.study.study_project.global.response.ApiResponse;

/** AI 품질 화면 — 거절 사유·AI 검수 효과·칸별 승인율·주별 추이. */
@RestController
@RequestMapping("/api/admin/quality")
@RequiredArgsConstructor
public class AdminQualityController {

    private final AdminQualityService adminQualityService;

    /** 예: {@code GET /api/admin/quality} */
    @GetMapping
    public ApiResponse<AdminQualityResponse> summary() {
        return ApiResponse.ok(adminQualityService.summary());
    }
}
