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
 * 토론방의 게시글 — DB의 {@code post} 테이블(V22).
 *
 * <p>방과 글쓴이를 연관관계가 아니라 id 칸으로 둔다({@link Comment}와 같은 이유).
 * 상태도 댓글과 같은 값을 쓴다 — 보임·가림·삭제의 뜻이 글과 댓글에서 다르지 않다.
 */
@Entity
@Table(name = "post")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discussion_id", nullable = false)
    private Long discussionId;

    /** 글쓴이 id. 탈퇴한 사용자의 글은 {@code null}이다(외래키 SET NULL). */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 5000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CommentStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막으로 고친 시각. 한 번도 안 고쳤으면 {@code null}. */
    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    private Post(Long discussionId, Long userId, String title, String body) {
        this.discussionId = discussionId;
        this.userId = userId;
        this.title = title;
        this.body = body;
        this.status = CommentStatus.VISIBLE;
    }

    public static Post of(Long discussionId, Long userId, String title, String body) {
        return new Post(discussionId, userId, title, body);
    }

    public void edit(String title, String body) {
        this.title = title;
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

    public boolean isDeleted() {
        return status == CommentStatus.DELETED;
    }
}
