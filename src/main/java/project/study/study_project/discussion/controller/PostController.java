package project.study.study_project.discussion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostListResponse;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.response.ApiResponse;

/**
 * 토론방 글 읽기 — 공개. 경로가 {@code /api/quiz} 아래인 이유는 {@link CommentController}와 같다.
 */
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** {@code userId}는 비로그인이면 null이다. 그때 solved·canWrite는 false로 나간다. */
    @GetMapping("/{problemId}/posts")
    public ApiResponse<PostListResponse> list(@PathVariable Long problemId,
                                              @AuthenticationPrincipal Long userId,
                                              @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(postService.list(problemId, userId, page));
    }

    @GetMapping("/posts/{postId}")
    public ApiResponse<PostDetail> detail(@PathVariable Long postId,
                                          @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(postService.detail(postId, userId));
    }
}
