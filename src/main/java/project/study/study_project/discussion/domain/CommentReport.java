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

    /** 신고가 들어온 순간의 글 제목(V27). 댓글 신고면 {@code null}이다. */
    @Column(name = "snapshot_title", length = 100)
    private String snapshotTitle;

    /**
     * 신고가 들어온 순간의 본문(V27). 글쓴이가 그 뒤에 고쳐도 관리자는 신고된 내용을 본다.
     * V27 전에 접수된 신고는 {@code null}이다.
     */
    @Column(name = "snapshot_body", length = 5000)
    private String snapshotBody;

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

    private CommentReport(Long commentId, Long postId, Long userId, CommentReportReason reason, String detail,
                          String snapshotTitle, String snapshotBody) {
        this.commentId = commentId;
        this.postId = postId;
        this.snapshotTitle = snapshotTitle;
        this.snapshotBody = snapshotBody;
        this.userId = userId;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING;
    }

    /** 댓글 신고. 지금의 본문을 베껴 둔다. */
    public static CommentReport of(Comment comment, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(comment.getId(), null, userId, reason, detail, null, comment.getBody());
    }

    /** 글 신고. 지금의 제목과 본문을 베껴 둔다. */
    public static CommentReport ofPost(Post post, Long userId, CommentReportReason reason, String detail) {
        return new CommentReport(null, post.getId(), userId, reason, detail, post.getTitle(), post.getBody());
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
