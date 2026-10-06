package project.study.study_project.admin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.dto.CommentReportItem;
import project.study.study_project.discussion.dto.ErrataPostItem;
import project.study.study_project.discussion.service.CommentReportService;
import project.study.study_project.discussion.service.PostService;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.report.domain.ReportStatus;

import java.util.Map;

/**
 * 댓글 신고함과 가림 — {@code /api/admin/**}이라 SecurityConfig의 {@code hasRole(ADMIN)}이 일괄 적용된다.
 *
 * <p>가림·복구·기각이 POST + 동사 경로인 것은 문제 제보함과 같은 판단이다 — 수정이 아니라 판정이다.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    private final CommentReportService commentReportService;
    private final PostService postService;

    @GetMapping("/comment-reports")
    public ApiResponse<PageResponse<CommentReportItem>> list(
            @RequestParam(required = false) ReportStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(commentReportService.getReports(status, pageable));
    }

    /** 대기 건수 — 관리 콘솔 메뉴의 배지. */
    @GetMapping("/comment-reports/pending-count")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.ok(Map.of("count", commentReportService.pendingCount()));
    }

    @PostMapping("/comment-reports/{id}/dismiss")
    public ApiResponse<CommentReportItem> dismiss(@PathVariable Long id,
                                                  @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.ok(commentReportService.dismiss(id, body != null ? body.get("note") : null));
    }

    @PostMapping("/comments/{id}/hide")
    public ApiResponse<Map<String, CommentStatus>> hide(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.hide(id)));
    }

    @PostMapping("/comments/{id}/restore")
    public ApiResponse<Map<String, CommentStatus>> restore(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.restore(id)));
    }

    /** 글 가림 — 글 아래 댓글도 함께 안 보이게 된다. */
    @PostMapping("/posts/{id}/hide")
    public ApiResponse<Map<String, CommentStatus>> hidePost(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.hidePost(id)));
    }

    @PostMapping("/posts/{id}/restore")
    public ApiResponse<Map<String, CommentStatus>> restorePost(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("status", commentReportService.restorePost(id)));
    }

    /**
     * 토론방의 "오류 지적" 글 — 제보 화면이 문제 제보와 나란히 보여 준다.
     * {@code unanswered=true}(기본)면 운영진 댓글이 아직 없는 글만 온다.
     */
    @GetMapping("/errata-posts")
    public ApiResponse<PageResponse<ErrataPostItem>> errataPosts(
            @RequestParam(defaultValue = "true") boolean unanswered,
            @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok(postService.errata(unanswered, page));
    }

    /** 답을 기다리는 오류 지적 글 수 — 제보 메뉴의 배지에 문제 제보 대기 수와 합쳐 보인다. */
    @GetMapping("/errata-posts/pending-count")
    public ApiResponse<Map<String, Long>> errataPendingCount() {
        return ApiResponse.ok(Map.of("count", postService.errataPendingCount()));
    }
}
