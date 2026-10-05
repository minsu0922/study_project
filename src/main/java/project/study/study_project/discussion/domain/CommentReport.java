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
 * 신고 한 건 — DB의 {@code comment_report} 테이블(V21, V25).
 *
 * <p>대상은 댓글이거나 글이다. {@code commentId}와 {@code postId} 가운데 하나만 채운다 —
 * DB가 이 규칙을 걸지 못해(V25 주석) 생성 메서드 둘이 지킨다.
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

    /** 신고된 댓글. 글 신고면 {@code null}이다. */
    @Column(name = "comment_id")
    private Long commentId;

    /** 신고된 글. 댓글 신고면 {@code null}이다. */
    @Column(name = "post_id")
    private Long postId;

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

    private CommentReport(Long commentId, Long postId, Long userId, CommentReportReason reason, String detail) {
        this.commentId = commentId;
        this.postId = postId;
        this.userId = userId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    public static CommentReport of(Long commentId, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(commentId, null, userId, reason, detail);
    }

    public static CommentReport ofPost(Long postId, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(null, postId, userId, reason, detail);
    }

    public boolean targetsPost() {
        return postId != null;
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
