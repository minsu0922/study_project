package project.study.study_project.admin.audit;

import java.time.LocalDateTime;

/**
 * 처리 기록 한 줄.
 *
 * @param action 사람이 읽는 이름. 이름표가 없는 주소는 "POST /api/admin/..." 그대로다
 */
public record AdminAuditItem(
        Long id,
        String actorUsername,
        String action,
        String path,
        LocalDateTime createdAt
) {
    static AdminAuditItem from(AdminAuditLog log) {
        return new AdminAuditItem(log.getId(), log.getActorUsername(),
                AdminActionLabels.of(log.getMethod(), log.getPattern()), log.getPath(), log.getCreatedAt());
    }
}
