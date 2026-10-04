package project.study.study_project.discussion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.CommentListResponse;
import project.study.study_project.discussion.service.CommentService;
import project.study.study_project.global.response.ApiResponse;

import java.util.List;
import java.util.Map;

/**
 * 토론 읽기 — 공개.
 *
 * <p>경로가 {@code /api/quiz} 아래인 이유: {@code GET /api/quiz/**}는 이미 공개 경로다
 * (SecurityConfig "화면과 문제는 누구나"). 문제에 딸린 토론도 같은 문을 쓴다.
 */
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class CommentController {

    /** 한 번에 물을 수 있는 문제 수 — 문제 목록의 한 쪽(20건)보다 넉넉하게 잡았다. */
    private static final int MAX_COUNT_IDS = 100;

    private final CommentService commentService;

    /** {@code userId}는 비로그인이면 null이다. 그때 solved·canWrite는 false로 나간다. */
    @GetMapping("/{problemId}/comments")
    public ApiResponse<CommentListResponse> list(@PathVariable Long problemId,
                                                 @AuthenticationPrincipal Long userId,
                                                 @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(commentService.list(problemId, userId, page));
    }

    /** 문제별 댓글 수. 예: {@code GET /api/quiz/comment-counts?problemIds=12,15,18} */
    @GetMapping("/comment-counts")
    public ApiResponse<Map<Long, Long>> counts(@RequestParam List<Long> problemIds) {
        List<Long> limited = problemIds.size() > MAX_COUNT_IDS ? problemIds.subList(0, MAX_COUNT_IDS) : problemIds;
        return ApiResponse.ok(commentService.counts(limited));
    }
}
