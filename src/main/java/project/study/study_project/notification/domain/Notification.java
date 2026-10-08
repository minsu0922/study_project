package project.study.study_project.notification.domain;

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

import java.time.LocalDateTime;

/** 알림 한 건(V31). 문구와 링크는 만들 때 굳힌다 — 원본이 지워져도 알림은 읽을 수 있다. */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    public static final int MESSAGE_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = MESSAGE_MAX)
    private String message;

    /** 눌렀을 때 갈 주소. 갈 곳이 없는 알림은 {@code null}. */
    @Column(length = 300)
    private String link;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Notification(Long userId, NotificationType type, String message, String link, LocalDateTime now) {
        this.userId = userId;
        this.type = type;
        this.message = message.length() > MESSAGE_MAX ? message.substring(0, MESSAGE_MAX) : message;
        this.link = link;
        this.createdAt = now;
    }

    public static Notification of(Long userId, NotificationType type, String message, String link,
                                  LocalDateTime now) {
        return new Notification(userId, type, message, link, now);
    }

    public boolean isRead() {
        return readAt != null;
    }

    /** 이미 읽은 알림은 처음 읽은 시각을 지킨다. */
    public void markRead(LocalDateTime now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
