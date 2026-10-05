package project.study.study_project.discussion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.domain.PostCategory;
import project.study.study_project.discussion.dto.PostDetail;
import project.study.study_project.discussion.dto.PostFilter;
import project.study.study_project.discussion.dto.PostListResponse;
import project.study.study_project.discussion.dto.PostSort;
import project.study.study_project.discussion.dto.RecentPostResponse;
import project.study.study_project.discussion.dto.RoomListResponse;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.common.DomainCode;
import project.study.study_project.global.response.ApiResponse;

import java.util.List;
import java.util.Map;

/**
 * 토론방 글 읽기 — 공개. 경로가 {@code /api/quiz} 아래인 이유는 {@link CommentController}와 같다.
 */
@RestController
@RequestMapping("/api/quiz")
@RequiredArgsConstructor
public class PostController {

    /** 한 번에 물을 수 있는 문제 수 — 문제 목록의 한 쪽(20건)보다 넉넉하게 잡았다. */
    private static final int MAX_COUNT_IDS = 100;

    private final PostService postService;

    /** 문제별 글 수. 예: {@code GET /api/quiz/post-counts?problemIds=12,15,18} */
    @GetMapping("/post-counts")
    public ApiResponse<Map<Long, Long>> counts(@RequestParam List<Long> problemIds) {
        List<Long> limited = problemIds.size() > MAX_COUNT_IDS ? problemIds.subList(0, MAX_COUNT_IDS) : problemIds;
        return ApiResponse.ok(postService.counts(limited));
    }

    /**
     * 모든 토론방의 글(한 쪽 20건). 커뮤니티 화면이 읽는다.
     * 예: {@code GET /api/quiz/posts?q=캐시&sort=comments&domain=SYSTEM_DESIGN&page=0}
     *
     * @param q      제목·본문에서 찾을 말
     * @param sort   {@code latest}(기본) 또는 {@code comments}
     * @param domain 분야 코드. 등록되지 않은 코드면 결과가 빈다
     * @param category 말머리({@code QUESTION}·{@code SUMMARY}·{@code ERRATA}). 모르는 값은 400
     * @param unanswered {@code true}면 보이는 댓글이 없는 글만("답변 기다리는 글" 탭)
     */
    @GetMapping("/posts")
    public ApiResponse<RecentPostResponse> recent(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String sort,
                                                  @RequestParam(required = false) DomainCode domain,
                                                  @RequestParam(required = false) PostCategory category,
                                                  @RequestParam(defaultValue = "false") boolean unanswered,
                                                  @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(postService.recent(
                PostFilter.community(q, PostSort.from(sort), domain, category, unanswered), page));
    }

    /** 토론방 목록 — 글이 있는 문제를 최근 글이 달린 방부터. 예: {@code GET /api/quiz/rooms?domain=NETWORK} */
    @GetMapping("/rooms")
    public ApiResponse<RoomListResponse> rooms(@RequestParam(required = false) DomainCode domain,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(postService.rooms(domain, page, size));
    }

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
