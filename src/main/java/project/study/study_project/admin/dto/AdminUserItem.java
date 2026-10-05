package project.study.study_project.admin.dto;

import project.study.study_project.user.domain.Role;
import project.study.study_project.user.domain.User;

import java.time.LocalDateTime;

/**
 * 관리자 화면의 사용자 한 줄. 비밀번호 해시는 어떤 모양으로도 싣지 않는다.
 *
 * @param suspended      지금 정지 중인지. 기간이 지난 정지는 false다
 * @param indefinite     무기한 정지인지
 * @param suspendedUntil 풀리는 시각. 정지가 아니거나 무기한이면 {@code null} — 9999년을 화면에 내보내지 않는다
 */
public record AdminUserItem(
        Long id,
        String username,
        String nickname,
        Role role,
        LocalDateTime createdAt,
        boolean suspended,
        boolean indefinite,
        LocalDateTime suspendedUntil,
        String suspendedReason
) {

    public static AdminUserItem of(User user, LocalDateTime now) {
        boolean suspended = user.isSuspended(now);
        boolean indefinite = suspended && user.isSuspendedIndefinitely();
        return new AdminUserItem(
                user.getId(), user.getUsername(), user.getNickname(), user.getRole(), user.getCreatedAt(),
                suspended, indefinite,
                suspended && !indefinite ? user.getSuspendedUntil() : null,
                suspended ? user.getSuspendedReason() : null);
    }
}
