package project.study.study_project.discussion.dto;

import project.study.study_project.discussion.domain.Comment;
import project.study.study_project.discussion.domain.CommentReport;
import project.study.study_project.discussion.domain.CommentReportReason;
import project.study.study_project.discussion.domain.CommentStatus;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

/**
 * 신고함 한 줄 — 관리자 화면이 읽는 모양.
 *
 * <p>가려진 글의 본문도 싣는다. 학습자 응답과 달리 관리자는 "무엇을 가렸는지" 봐야 복구할지 정한다.
 * 신고자는 싣지 않는다 — 누가 냈는지가 보이면 판단이 내용이 아니라 사람에 끌린다.
 *
 * @param commentNickname 글쓴이 닉네임. 탈퇴했으면 {@code null}
 * @param problemId       그 글이 달린 문제. 문제가 지워지면 신고도 함께 지워지므로 늘 값이 있다
 */
public record CommentReportItem(
        Long id,
        Long commentId,
        String commentBody,
        CommentStatus commentStatus,
        String commentNickname,
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

    public static CommentReportItem of(CommentReport report, Comment comment, String commentNickname,
                                       Long problemId, String problemTitle) {
        return new CommentReportItem(
                report.getId(), comment.getId(), comment.getBody(), comment.getStatus(), commentNickname,
                problemId, problemTitle,
                report.getReason(), report.getReason().getLabel(), report.getDetail(),
                report.getStatus(), report.getAdminNote(), report.getCreatedAt(), report.getResolvedAt());
    }
}
