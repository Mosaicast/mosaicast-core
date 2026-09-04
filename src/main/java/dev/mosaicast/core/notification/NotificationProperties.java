// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bounds on the inbox (ARCHITECTURE §17.2).
 *
 * @param readRetention   how long a read notification is kept before the sweep drops it
 * @param maxUnreadPerUser the most unread rows one user may accumulate; the oldest are trimmed past it
 * @param maxTextLength    the longest a single message may be, in characters
 */
@ConfigurationProperties(prefix = "mosaicast.notifications")
public record NotificationProperties(
        Duration readRetention,
        Integer maxUnreadPerUser,
        Integer maxTextLength) {

    public NotificationProperties {
        readRetention = readRetention == null ? Duration.ofDays(30) : readRetention;
        // Generous, because the cap is a backstop against an unbounded table rather than a UX rule: a user
        // who hits it has a plugin behaving badly, and the rate limits (§17.1) should have caught that first.
        maxUnreadPerUser = maxUnreadPerUser == null ? 200 : maxUnreadPerUser;
        maxTextLength = maxTextLength == null ? 500 : maxTextLength;
    }
}
