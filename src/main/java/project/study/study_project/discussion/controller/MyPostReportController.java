package project.study.study_project.discussion.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.ReportReceipt;
import project.study.study_project.discussion.dto.PostReportRequest;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 글 신고 — 로그인 필수({@code /api/me/**}). 문제를 풀지 않은 사람도 한다. 읽을 수 있으면 신고도 할 수 있다.
 */
@RestController
@RequestMapping("/api/me/post-reports")
@RequiredArgsConstructor
public class MyPostReportController {

    private final CommentReportService commentReportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportReceipt> report(@AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody PostReportRequest request) {
        return ApiResponse.ok(commentReportService.reportPost(userId, request));
    }
}
