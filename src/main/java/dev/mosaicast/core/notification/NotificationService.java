// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the inbox (ARCHITECTURE §17).
 *
 * <p>Every source goes through here, which is the design: the host is the sender whoever the message is
 * from, so the cap, the retention clock and the text limit are enforced in one place and there is nowhere
 * else for a caller to write a row.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final NotificationProperties properties;

    public NotificationService(NotificationRepository notifications, NotificationProperties properties) {
        this.notifications = notifications;
        this.properties = properties;
    }

    /**
     * Tells a user about something core did (§17).
     *
     * @param userId who to tell
     * @param kind   the fixed message type the shell translates
     * @param params values substituted into it
     */
    @Transactional
    public void system(UUID userId, NotificationKind kind, Map<String, String> params) {
        deliver(Notification.system(userId, kind, params));
    }

    /**
     * Delivers a warning an admin wrote (§17).
     *
     * @param userId  who to warn
     * @param text    what it says — free text, unlike a system message
     * @param adminId who wrote it, for the log
     */
    @Transactional
    public void adminWarning(UUID userId, String text, UUID adminId) {
        deliver(Notification.admin(userId, truncate(text)));
        // Logged like a role change (§8.5): a warning is a moderation act and "they were told" is the
        // point of it, so it needs a record outside the row the recipient can mark read.
        log.info("Admin {} warned user {}", adminId, userId);
    }

    /**
     * Delivers one plugin's message (§17.1).
     *
     * <p>Eligibility, rate limits and link validation happen before this — they are the plugin surface's
     * job, and this method is what that surface calls once a recipient has passed them.
     *
     * @param userId   who to tell
     * @param pluginId whose message it is
     * @param text     one finished sentence per locale code
     * @param link     an already-validated internal path, or null
     */
    @Transactional
    public void fromPlugin(UUID userId, String pluginId, Map<String, String> text, String link) {
        Map<String, String> capped = text.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> truncate(entry.getValue())));
        deliver(Notification.fromPlugin(userId, pluginId, capped, link));
    }

    /** One user's inbox, newest first. */
    @Transactional(readOnly = true)
    public Page<Notification> inbox(UUID userId, int page, int size) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
    }

    /** What the bell shows. */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * Marks one notification read.
     *
     * <p>Scoped to its owner, so the id in the path is not enough on its own — otherwise anyone could mark
     * anyone else's inbox read, which is a small act with an outsized effect on an admin warning.
     *
     * @return whether there was such a row belonging to this user
     */
    @Transactional
    public boolean markRead(UUID userId, UUID notificationId) {
        return notifications.findByIdAndUserId(notificationId, userId)
                .map(notification -> {
                    notification.markRead(Instant.now());
                    notifications.save(notification);
                    return true;
                })
                .orElse(false);
    }

    /** Marks the caller's whole inbox read. */
    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, Instant.now());
    }

    /** Drops everything addressed to a user — account erasure (§12.8, §17.2). */
    @Transactional
    public long deleteForUser(UUID userId) {
        return notifications.deleteByUserId(userId);
    }

    /** Drops everything one plugin sent, when that plugin's data goes (§17.2). */
    @Transactional
    public long deleteFromPlugin(String pluginId) {
        return notifications.deleteBySource(Notification.SOURCE_PLUGIN_PREFIX + pluginId);
    }

    /**
     * The retention sweep (§17.2): read notifications past their keep-time.
     *
     * @return how many rows went
     */
    @Transactional
    public int purgeExpired() {
        return notifications.deleteReadBefore(Instant.now().minus(properties.readRetention()));
    }

    /**
     * Writes the row, then trims the user's unread backlog to the cap.
     *
     * <p>Trimming the <em>oldest</em> unread rather than refusing the newest is deliberate: a user who has
     * stopped reading their bell should still see what just happened, and the alternative silently drops
     * the message somebody is being told right now.
     */
    private void deliver(Notification notification) {
        notifications.save(notification);

        UUID userId = notification.getUserId();
        // Counted first, and off the partial index, so the ordinary case — an inbox nowhere near the cap —
        // costs one cheap count and reads no rows at all.
        int excess = (int) (notifications.countByUserIdAndReadAtIsNull(userId)
                - properties.maxUnreadPerUser());
        if (excess > 0) {
            List<UUID> oldest = notifications.unreadOldestFirst(userId, PageRequest.of(0, excess));
            notifications.deleteAllByIdInBatch(oldest);
            log.debug("Trimmed {} unread notification(s) for user {}", oldest.size(), userId);
        }
    }

    /** Caps one message's length; the limit is the host's, so no source can write an unbounded string. */
    private String truncate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= properties.maxTextLength()
                ? value
                : value.substring(0, properties.maxTextLength());
    }
}
