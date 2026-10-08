package project.study.study_project.quiz.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.quiz.dto.StudyTrendDay;
import project.study.study_project.quiz.dto.SubmissionHistoryItem;
import project.study.study_project.quiz.service.StudyHistoryService;

import java.util.List;

/** 내 학습 추이와 풀이 이력. 경로가 /api/me/** 라 로그인 사용자만 닿는다(SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class StudyHistoryController {

    private final StudyHistoryService studyHistoryService;

    /** 예: {@code GET /api/me/study-trend?days=30} — 오래된 날부터 오늘까지. */
    @GetMapping("/api/me/study-trend")
    public ApiResponse<List<StudyTrendDay>> trend(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "" + StudyHistoryService.DEFAULT_TREND_DAYS) int days) {
        return ApiResponse.ok(studyHistoryService.trend(userId, days));
    }

    /** 예: {@code GET /api/me/submissions?correct=true&page=0} */
    @GetMapping("/api/me/submissions")
    public ApiResponse<PageResponse<SubmissionHistoryItem>> history(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Boolean correct,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(studyHistoryService.history(userId, correct, pageable));
    }
}
