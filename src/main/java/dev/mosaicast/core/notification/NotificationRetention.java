// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops read notifications once they are past their keep-time (ARCHITECTURE §17.2).
 *
 * <p>Only <em>read</em> ones. An unread notification is something the site still has to say, however old,
 * and the backlog is bounded by the per-user cap instead. Read ones have done their job, and a table that
 * only grows is the thing §17.2 asks to be avoided.
 *
 * <p>{@code @SchedulerLock} because §13 puts app instances behind a load balancer at v3 and a sweep that
 * runs on all of them at once is a lock contention problem nobody debugs until it happens.
 */
@Component
public class NotificationRetention {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetention.class);

    private final NotificationService notifications;

    public NotificationRetention(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The daily sweep; the initial delay keeps it out of the way of a boot. */
    @Scheduled(fixedDelayString = "${mosaicast.notifications.purge-interval-ms:86400000}",
            initialDelay = 300_000)
    @SchedulerLock(name = "notification-retention", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purge() {
        int removed = notifications.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} read notification(s) past retention", removed);
        }
    }
}
