package project.study.study_project.notification.dto;

import project.study.study_project.notification.domain.Notification;
import project.study.study_project.notification.domain.NotificationType;

import java.time.LocalDateTime;

public record NotificationItem(
        Long id,
        NotificationType type,
        String message,
        String link,
        boolean read,
        LocalDateTime createdAt
) {
    public static NotificationItem from(Notification n) {
        return new NotificationItem(n.getId(), n.getType(), n.getMessage(), n.getLink(),
                n.isRead(), n.getCreatedAt());
    }
}
