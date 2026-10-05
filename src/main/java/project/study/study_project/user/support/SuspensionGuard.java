package project.study.study_project.user.support;

import project.study.study_project.global.exception.BusinessException;
import project.study.study_project.global.exception.ErrorCode;
import project.study.study_project.user.domain.User;

import java.time.LocalDateTime;

/**
 * 정지된 사용자의 쓰기를 거절한다 — 글, 댓글, 신고가 같은 문을 쓴다.
 *
 * <p>검사를 한곳에 두는 이유: 쓰기 경로가 여섯 군데다. 각자 적으면 거절 문구가 갈리고,
 * 새 쓰기 경로를 만들 때 무엇을 불러야 하는지가 이름으로 남지 않는다.
 */
public final class SuspensionGuard {

    private SuspensionGuard() {
    }

    public static void requireNotSuspended(User user) {
        String notice = noticeFor(user);
        if (notice != null) {
            throw new BusinessException(ErrorCode.DISCUSSION_013, notice);
        }
    }

    /**
     * 정지 안내 문구. 정지가 아니면(사용자가 없어도) {@code null}이다.
     *
     * <p>언제 풀리는지와 왜 정지됐는지를 싣는다. 모르면 사용자는 고장으로 안다.
     * 거절 응답과 화면의 안내가 같은 문장을 쓰게 여기 한 곳에서 만든다.
     */
    public static String noticeFor(User user) {
        if (user == null || !user.isSuspended(LocalDateTime.now())) {
            return null;
        }
        String period = user.isSuspendedIndefinitely()
                ? "무기한 정지입니다."
                : user.getSuspendedUntil().toLocalDate() + "까지 쓸 수 없습니다.";
        return "글쓰기가 정지된 계정입니다. " + period + " 사유: " + user.getSuspendedReason();
    }
}
