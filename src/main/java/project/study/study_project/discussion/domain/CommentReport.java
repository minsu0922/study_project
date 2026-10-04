package project.study.study_project.discussion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import project.study.study_project.report.domain.ReportStatus;

import java.time.LocalDateTime;

/**
 * 댓글 신고 한 건 — DB의 {@code comment_report} 테이블(V21).
 *
 * <p>상태는 문제 제보의 {@link ReportStatus}를 그대로 쓴다. 뜻이 같다 — 대기, 인정(가림), 기각.
 */
@Entity
@Table(name = "comment_report")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CommentReportReason reason;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ReportStatus status;

    @Column(name = "admin_note", length = 500)
    private String adminNote;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    private CommentReport(Long commentId, Long userId, CommentReportReason reason, String detail) {
        this.commentId = commentId;
        this.userId = userId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    public static CommentReport of(Long commentId, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(commentId, userId, reason, detail);
    }

    public void dismiss(String adminNote) {
        this.status = ReportStatus.DISMISSED;
        this.adminNote = adminNote;
        this.resolvedAt = LocalDateTime.now();
    }

    public boolean isPending() {
        return status == ReportStatus.PENDING;
    }
}
