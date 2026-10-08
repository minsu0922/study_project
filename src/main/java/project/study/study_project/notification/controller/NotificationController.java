package project.study.study_project.notification.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.study.study_project.global.response.ApiResponse;
import project.study.study_project.global.response.PageResponse;
import project.study.study_project.notification.dto.NotificationItem;
import project.study.study_project.notification.service.NotificationService;

import java.util.Map;

/** 내 알림함. 경로가 /api/me/** 라 로그인 사용자만 닿는다(SecurityConfig). */
@RestController
@RequestMapping("/api/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationItem>> list(@AuthenticationPrincipal Long userId,
                                                            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(notificationService.list(userId, pageable));
    }

    /** 헤더의 종 배지용 — 모든 화면이 부르므로 숫자 하나만 준다. */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(userId)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationService.markRead(userId, id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal Long userId) {
        notificationService.markAllRead(userId);
        return ApiResponse.ok(null);
    }
}
