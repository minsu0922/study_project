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
        /** 등록 요청이 새로 만든 것의 번호. 등록이 아니면 {@code null}. */
        Long createdId,
        /** 응답 상태. 200대가 아니면 실패했거나 거부된 시도다. */
        int status,
        LocalDateTime createdAt
) {
    static AdminAuditItem from(AdminAuditLog log) {
        return new AdminAuditItem(log.getId(), log.getActorUsername(),
                AdminActionLabels.of(log.getMethod(), log.getPattern()), log.getPath(), log.getCreatedId(),
                log.getStatus(), log.getCreatedAt());
    }
}
