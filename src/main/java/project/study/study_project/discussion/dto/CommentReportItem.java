package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentReportReason;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.discussion.domain.Post;
import project.study.study_project.report.domain.ReportStatus;

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
 * @param targetBody     신고된 글 또는 댓글의 본문
 * @param targetNickname 글쓴이 닉네임. 탈퇴했으면 {@code null}
 * @param problemId      그 글이 속한 토론방의 문제. 문제가 지워지면 신고도 함께 지워지므로 늘 값이 있다
 */
public record CommentReportItem(
        Long id,
        String targetType,
        Long commentId,
        Long postId,
        String postTitle,
        String targetBody,
        CommentStatus targetStatus,
        String targetNickname,
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

    public static CommentReportItem ofComment(CommentReport report, Comment comment, Post post, String nickname,
                                              Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), "COMMENT", comment.getId(), post.getId(), post.getTitle(),
                comment.getBody(), comment.getStatus(), nickname, problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }

    public static CommentReportItem ofPost(CommentReport report, Post post, String nickname,
                                           Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), "POST", null, post.getId(), post.getTitle(),
                post.getBody(), post.getStatus(), nickname, problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }
}
