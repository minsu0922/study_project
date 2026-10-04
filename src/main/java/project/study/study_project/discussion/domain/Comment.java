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

import java.time.LocalDateTime;

/**
 * 댓글과 답글 — DB의 {@code comment} 테이블(V21).
 *
 * <p>방·글쓴이·부모를 연관관계가 아니라 id 칸으로 둔다. 목록은 방 id로 한 번, 답글은 부모 id 묶음으로
 * 한 번 읽으므로 객체 탐색이 필요 없고, {@code userId}는 탈퇴하면 NULL이 된다(ProblemReport와 같은 방식).
 */
@Entity
@Table(name = "comment")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discussion_id", nullable = false)
    private Long discussionId;

    /** 글쓴이 id. 탈퇴한 사용자의 글은 {@code null}이다(외래키 SET NULL). */
    @Column(name = "user_id")
    private Long userId;

    /** {@code null}이면 댓글, 값이 있으면 그 댓글의 답글이다. 답글의 답글은 없다. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommentStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막으로 고친 시각. 한 번도 안 고쳤으면 {@code null} — 화면의 "수정됨" 표시가 이 값을 본다. */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    private Comment(Long discussionId, Long userId, Long parentId, String body) {
        this.discussionId = discussionId;
        this.userId = userId;
        this.parentId = parentId;
        this.body = body;
        this.status = CommentStatus.VISIBLE;
    }

    public static Comment of(Long discussionId, Long userId, Long parentId, String body) {
        return new Comment(discussionId, userId, parentId, body);
    }

    public void edit(String body) {
        this.body = body;
        this.editedAt = LocalDateTime.now();
    }

    public void delete() {
        this.status = CommentStatus.DELETED;
    }

    public void hide() {
        this.status = CommentStatus.HIDDEN;
    }

    public void restore() {
        this.status = CommentStatus.VISIBLE;
    }

    public boolean isVisible() {
        return status == CommentStatus.VISIBLE;
    }
}
