package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentReportReason;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.report.domain.ReportStatus;
import project.study.study_project.user.domain.User;

import java.time.LocalDateTime;

/**
 * 신고함 한 줄 — 관리자 화면이 읽는 모양. 대상은 글이거나 댓글이다.
 *
 * <p>가려진 글의 본문도 싣는다. 학습자 응답과 달리 관리자는 "무엇을 가렸는지" 봐야 복구할지 정한다.
 * 신고자는 싣지 않는다 — 누가 냈는지가 보이면 판단이 내용이 아니라 사람에 끌린다.
 *
 * @param targetType     {@code "POST"} 또는 {@code "COMMENT"}. 화면이 가림·복구를 어느 주소로 부를지 정한다
 * @param commentId      신고된 댓글. 글 신고면 {@code null}
 * @param postId         신고된 글, 댓글 신고면 그 댓글이 달린 글. 관리자가 글 화면으로 건너간다
 * @param targetBody     신고된 글 또는 댓글의 <b>지금</b> 본문
 * @param reportedTitle  신고가 들어온 순간의 글 제목. 댓글 신고면 {@code null}
 * @param reportedBody   신고가 들어온 순간의 본문. 남겨 둔 것이 없는 옛 신고는 지금 본문과 같다
 * @param editedAfterReport 신고 뒤에 글쓴이가 내용을 고쳤는지 — 화면이 "신고 당시"와 "지금"을 나란히 보여 준다
 * @param targetNickname 글쓴이 닉네임. 탈퇴했으면 {@code null}
 * @param targetUserId   글쓴이 id. 관리 화면이 그 사람의 상세로 가는 링크를 만든다. 탈퇴했으면 {@code null}
 * @param targetUsername  글쓴이 아이디. 관리자가 사용자 화면에서 이 사람을 찾아 정지한다. 탈퇴했으면 {@code null}
 * @param targetSuspended 글쓴이가 지금 정지 중인지 — 이미 정지한 사람을 또 정지하러 가지 않게 한다
 * @param problemId      그 글이 속한 토론방의 문제. 문제가 지워지면 신고도 함께 지워지므로 늘 값이 있다
 */
public record CommentReportItem(
        Long id,
        String targetType,
        Long commentId,
        Long postId,
        String postTitle,
        String targetBody,
        String reportedTitle,
        String reportedBody,
        boolean editedAfterReport,
        CommentStatus targetStatus,
        String targetNickname,
        Long targetUserId,
        String targetUsername,
        boolean targetSuspended,
        Long problemId,
        String problemTitle,
        CommentReportReason reason,
        String reasonLabel,
        String detail,
        ReportStatus status,
        String adminNote,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {

    public static CommentReportItem ofComment(CommentReport report, Comment comment, Post post, User author,
                                              Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), "COMMENT", comment.getId(), post.getId(), post.getTitle(),
                comment.getBody(), null, reportedBody(report, comment.getBody()),
                edited(report.getSnapshotBody(), comment.getBody()),
                comment.getStatus(), nicknameOf(author), idOf(author), usernameOf(author), suspended(author),
                problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }

    public static CommentReportItem ofPost(CommentReport report, Post post, User author,
                                           Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), "POST", null, post.getId(), post.getTitle(),
                post.getBody(), report.getSnapshotBody() == null ? post.getTitle() : report.getSnapshotTitle(),
                reportedBody(report, post.getBody()),
                edited(report.getSnapshotBody(), post.getBody()) || edited(report.getSnapshotTitle(), post.getTitle()),
                post.getStatus(), nicknameOf(author), idOf(author), usernameOf(author), suspended(author),
                problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }

    /** 남겨 둔 것이 없는 옛 신고(V27 전)는 지금 본문으로 답한다. */
    private static String reportedBody(CommentReport report, String current) {
        return report.getSnapshotBody() == null ? current : report.getSnapshotBody();
    }

    /** 남겨 둔 것이 없으면 고쳤는지 알 수 없다 — 고치지 않은 것으로 본다. */
    private static boolean edited(String snapshot, String current) {
        return snapshot != null && !snapshot.equals(current);
    }

    /* 글쓴이가 탈퇴했으면 author가 null이다. */

    private static String nicknameOf(User author) {
        return author == null ? null : author.getNickname();
    }

    private static Long idOf(User author) {
        return author == null ? null : author.getId();
    }

    private static String usernameOf(User author) {
        return author == null ? null : author.getUsername();
    }

    private static boolean suspended(User author) {
        return author != null && author.isSuspended(LocalDateTime.now());
    }
}
