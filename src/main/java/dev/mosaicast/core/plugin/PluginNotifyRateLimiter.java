// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.web.FixedWindowRateLimiter;
import dev.mosaicast.plugin.api.NotificationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * How much one plugin may say, and to whom (ARCHITECTURE §17.1).
 *
 * <p><strong>The limits are the host's.</strong> A plugin declares what it <em>asks</em> for in its
 * manifest and the operator's ceiling is what it gets — but neither number is enforced by the plugin,
 * because a limit a plugin enforces is a limit a plugin can drop, and this is the one surface where
 * dropping it reaches other people.
 *
 * <p>Two limits, because they stop different things. The <strong>per-recipient</strong> window stops one
 * user being buried by a plugin that has confused a loop for a schedule; exhausting it costs that user
 * their message and nobody else theirs. The <strong>batch ceiling</strong> stops a plugin reaching the
 * whole site at once, and is checked before any row is written — a half-applied send would be notified
 * twice by a sender that retried.
 *
 * <p>In-memory per instance, like every other limiter here until Redis arrives at v3 (§13).
 */
@Component
public class PluginNotifyRateLimiter {

    /** How many notifications one plugin may send one user inside {@link #RECIPIENT_WINDOW}. */
    private static final int PER_RECIPIENT = 5;

    private static final Duration RECIPIENT_WINDOW = Duration.ofDays(1);

    /** The most recipients one call may reach — a page of a leaderboard, not a mailing list. */
    private static final int MAX_BATCH = 200;

    private final FixedWindowRateLimiter recipients = new FixedWindowRateLimiter();

    /**
     * Whether this plugin may notify this user right now.
     *
     * @return false when that one recipient's window is spent; the rest of the batch is unaffected
     */
    public boolean tryRecipient(String pluginId, UUID userId) {
        return recipients.check(pluginId + "|" + userId, PER_RECIPIENT, RECIPIENT_WINDOW,
                Instant.now()).allowed();
    }

    /**
     * Refuses a batch aimed at more users than one call may reach.
     *
     * @throws NotificationException {@code RATE_LIMITED}, which the SDK marks retryable — a scheduled
     *                               sender is expected to hold the batch and split it, not drop it
     */
    public void checkBatch(String pluginId, int recipientCount) throws NotificationException {
        if (recipientCount > MAX_BATCH) {
            throw new NotificationException(NotificationException.Reason.RATE_LIMITED,
                    "A notification may reach at most " + MAX_BATCH + " users in one call");
        }
    }
}
