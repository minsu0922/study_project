package project.study.study_project.admin.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 관리자가 한 쓰기 요청 한 건(V35). 한 번 적으면 고치지 않는다 — 수정 메서드가 없다. */
@Entity
@Table(name = "admin_audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_username", nullable = false, length = 30)
    private String actorUsername;

    @Column(nullable = false, length = 10)
    private String method;

    /** 컨트롤러에 적힌 주소 틀. 같은 종류의 일을 묶는 열쇠다. */
    @Column(nullable = false, length = 200)
    private String pattern;

    /** 실제로 불린 주소. 어느 대상이었는지가 여기 들어 있다. */
    @Column(nullable = false, length = 300)
    private String path;

    /** 등록 요청이 새로 만든 것의 번호(V37). 등록이 아니면 {@code null} — 대상은 path에 있다. */
    @Column(name = "created_id")
    private Long createdId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private AdminAuditLog(Long actorId, String actorUsername, String method, String pattern, String path,
                          Long createdId, LocalDateTime now) {
        this.actorId = actorId;
        this.actorUsername = actorUsername;
        this.method = method;
        this.pattern = pattern;
        this.path = path;
        this.createdId = createdId;
        this.createdAt = now;
    }

    public static AdminAuditLog of(Long actorId, String actorUsername, String method, String pattern,
                                   String path, Long createdId, LocalDateTime now) {
        return new AdminAuditLog(actorId, actorUsername, method, pattern, path, createdId, now);
    }
}
