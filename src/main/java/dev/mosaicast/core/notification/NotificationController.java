// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.PagedResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own inbox (ARCHITECTURE §17).
 *
 * <p>Under {@code /api/me}, so the existing {@code /api/me/**} rule authenticates it and there is no id in
 * any path — a notification belongs to exactly one person and nobody else has a reason to name it.
 */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * One notification as the shell draws it.
     *
     * <p>{@code kind} and {@code payload} travel separately rather than pre-rendered, because who
     * translates what depends on the source: a {@code system} row names a kind the shell owns the wording
     * for, an {@code admin} row carries text somebody wrote, and a plugin row carries a sentence per
     * locale that the shell picks from (§17.1). The shell is the only place that knows the reader's
     * current language, which is the whole reason none of this is resolved at send time.
     *
     * @param id        the notification
     * @param source    {@code system}, {@code admin} or {@code plugin:<id>}
     * @param kind      the system message type, or null
     * @param payload   the source-shaped body: system parameters, admin {@code text}, or locale → sentence
     * @param link      an internal path to follow, or null
     * @param createdAt when it was written
     * @param readAt    when it was read, or null while unread
     */
    public record NotificationView(UUID id, String source, String kind, Map<String, String> payload,
                                   String link, Instant createdAt, Instant readAt) {

        static NotificationView of(Notification notification) {
            return new NotificationView(
                    notification.getId(), notification.getSource(), notification.getKind(),
                    notification.getPayload(), notification.getLink(),
                    notification.getCreatedAt(), notification.getReadAt());
        }
    }

    /** How many unread — what the bell shows, asked on every page load, so it is its own cheap endpoint. */
    public record UnreadCount(long unread) {
    }

    @GetMapping
    public PagedResponse<NotificationView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return PagedResponse.of(
                notifications.inbox(currentUserId(authentication), PagedResponse.page(page),
                        PagedResponse.size(size)),
                NotificationView::of);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(Authentication authentication) {
        return new UnreadCount(notifications.unreadCount(currentUserId(authentication)));
    }

    /** Marks one read. A 404 for somebody else's id, which is also the answer for one that never existed. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        if (!notifications.markRead(currentUserId(authentication), id)) {
            throw new NotFoundException("No such notification: " + id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read")
    public UnreadCount markAllRead(Authentication authentication) {
        UUID userId = currentUserId(authentication);
        notifications.markAllRead(userId);
        return new UnreadCount(notifications.unreadCount(userId));
    }

    private static UUID currentUserId(Authentication authentication) {
        return CurrentUser.id(authentication)
                .orElseThrow(() -> new NotFoundException("Not authenticated"));
    }
}
