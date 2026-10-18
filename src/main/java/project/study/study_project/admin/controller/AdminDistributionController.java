package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.admin.dto.AdminDistributionResponse;
import project.study.study_project.admin.service.AdminDistributionService;
import project.study.study_project.global.response.ApiResponse;

/** 출제 분포 화면 — 공개 기준 칸·유형·문서별 문제 수. */
@RestController
@RequestMapping("/api/admin/distribution")
@RequiredArgsConstructor
public class AdminDistributionController {

    private final AdminDistributionService adminDistributionService;

    /** 예: {@code GET /api/admin/distribution} */
    @GetMapping
    public ApiResponse<AdminDistributionResponse> summary() {
        return ApiResponse.ok(adminDistributionService.summary());
    }
}
