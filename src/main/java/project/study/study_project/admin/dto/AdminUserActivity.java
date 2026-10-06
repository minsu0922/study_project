package project.study.study_project.admin.dto;

import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 화면의 사용자 활동 — 이 사람을 정지할지 판단하는 근거.
 *
 * @param postCount           쓴 글 수. 지운 글은 빼고 가려진 글은 넣는다
 * @param commentCount        쓴 댓글 수. 세는 법은 글과 같다
 * @param hiddenCount         관리자가 가린 글과 댓글의 수 — 이미 문제가 된 적이 몇 번인가
 * @param receivedReportCount 이 사람의 글·댓글이 받은 신고 수(기각된 것도 센다)
 * @param attemptedProblems   한 번이라도 풀어 본 문제 수
 * @param solvedProblems      맞힌 적이 있는 문제 수
 */
public record AdminUserActivity(
        long postCount,
        long commentCount,
        long hiddenCount,
        long receivedReportCount,
        long attemptedProblems,
        long solvedProblems,
        List<PostLine> recentPosts,
        List<ReportLine> recentReports
) {

    /** 최근 글 한 줄. 가려진 글도 제목을 싣는다 — 관리자는 무엇이 가려졌는지 봐야 한다. */
    public record PostLine(Long id, String categoryLabel, String title, CommentStatus status,
                           LocalDateTime createdAt) {
    }

    /**
     * 받은 신고 한 줄.
     *
     * @param targetType {@code "POST"} 또는 {@code "COMMENT"}
     * @param postId     신고된 글, 또는 신고된 댓글이 달린 글 — 화면이 그 글로 가는 링크를 만든다
     * @param excerpt    신고 당시 본문의 앞부분
     */
    public record ReportLine(Long id, String targetType, Long postId, String reasonLabel, ReportStatus status,
                             String excerpt, LocalDateTime createdAt) {
    }
}
