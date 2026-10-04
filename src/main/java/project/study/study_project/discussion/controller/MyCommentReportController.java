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
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.CommentReportRequest;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.global.response.ApiResponse;

/** 댓글 신고 접수 — 로그인 필수({@code /api/me/**}). 문제를 풀지 않아도 신고할 수 있다. */
@RestController
@RequestMapping("/api/me/comment-reports")
@RequiredArgsConstructor
public class MyCommentReportController {

    private final CommentReportService commentReportService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentReportItem> report(@AuthenticationPrincipal Long userId,
                                                 @Valid @RequestBody CommentReportRequest request) {
        return ApiResponse.ok(commentReportService.report(userId, request));
    }
}
