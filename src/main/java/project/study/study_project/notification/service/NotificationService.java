package project.study.study_project.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.notification.domain.Notification;
import project.study.study_project.notification.domain.NotificationType;
import project.study.study_project.notification.dto.NotificationItem;
import project.study.study_project.notification.repository.NotificationRepository;

import java.time.LocalDateTime;

/**
 * 알림함(V31).
 *
 * <p>알림은 원인이 된 쓰기(댓글 작성·제보 판정)와 <b>같은 트랜잭션</b>에서 남긴다.
 * 따로 떼면 댓글은 저장됐는데 알림만 빠지는 경우가 생기고, 그건 화면에서 드러나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * 알림을 남긴다. 받는 사람이 없거나(탈퇴) 자기 행동이면 남기지 않는다.
     *
     * @param recipientId 받는 사람. 탈퇴한 작성자의 글이면 {@code null}이 온다
     * @param actorId     알림을 일으킨 사람. 관리자 판정처럼 따질 필요가 없으면 {@code null}
     */
    @Transactional
    public void notify(Long recipientId, Long actorId, NotificationType type, String message, String link) {
        if (recipientId == null || recipientId.equals(actorId)) {
            return;
        }
        notificationRepository.save(Notification.of(recipientId, type, message, link, LocalDateTime.now()));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationItem> list(Long userId, Pageable pageable) {
        return PageResponse.from(notificationRepository.findByUserIdOrderByIdDesc(userId, pageable)
                .map(NotificationItem::from));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** 없는 알림이나 남의 알림은 조용히 지나간다 — 읽음 표시 실패를 사용자에게 보여 줄 이유가 없다. */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        notificationRepository.findByIdAndUserId(notificationId, userId)
                .ifPresent(n -> n.markRead(LocalDateTime.now()));
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId, LocalDateTime.now());
    }
}
