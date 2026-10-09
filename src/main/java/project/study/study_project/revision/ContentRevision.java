package project.study.study_project.revision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** 문제·문서를 고치기 직전의 모습 한 벌(V36). 한 번 적으면 고치지 않는다. */
@Entity
@Table(name = "content_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private RevisionTarget targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 관리 등록 요청과 같은 모양의 JSON — 되돌리기는 이것을 수정 요청으로 다시 넣는 일이다. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "snapshot_json", nullable = false)
    private String snapshotJson;

    @Column(name = "editor_username", length = 30)
    private String editorUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private ContentRevision(RevisionTarget targetType, Long targetId, String snapshotJson,
                            String editorUsername, LocalDateTime now) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.snapshotJson = snapshotJson;
        this.editorUsername = editorUsername;
        this.createdAt = now;
    }

    public static ContentRevision of(RevisionTarget targetType, Long targetId, String snapshotJson,
                                     String editorUsername, LocalDateTime now) {
        return new ContentRevision(targetType, targetId, snapshotJson, editorUsername, now);
    }
}
