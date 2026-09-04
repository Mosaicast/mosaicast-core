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
 * manifest and the operator's ceiling is what it gets — the same ask-and-cap shape as blob quotas (§11.1).
 * Neither number is enforced by the plugin, because a limit a plugin enforces is a limit a plugin can
 * drop, and this is the one surface where dropping it reaches other people.
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

    private static final Duration RECIPIENT_WINDOW = Duration.ofDays(1);

    private final FixedWindowRateLimiter recipients = new FixedWindowRateLimiter();
    private final PluginNotifyProperties properties;

    public PluginNotifyRateLimiter(PluginNotifyProperties properties) {
        this.properties = properties;
    }

    /**
     * The daily allowance this plugin actually has for one recipient.
     *
     * <p>The smaller of what the manifest asked for and what the operator permits, so a plugin may hold
     * itself to less than the ceiling but never reach past it. A manifest that asks for nothing gets the
     * operator's default rather than zero — an absent number is "no opinion", not "never send".
     *
     * @param manifest the sending plugin's manifest
     */
    public int perRecipientAllowance(PluginManifest manifest) {
        Integer asked = manifest.notifications() == null ? null : manifest.notifications().perUserPerDay();
        int wanted = asked == null || asked <= 0 ? properties.defaultPerUserPerDay() : asked;
        return Math.min(wanted, properties.hardPerUserPerDay());
    }

    /**
     * Whether this plugin may notify this user right now.
     *
     * @return false when that one recipient's window is spent; the rest of the batch is unaffected
     */
    public boolean tryRecipient(String pluginId, UUID userId, int allowance) {
        return recipients.check(pluginId + "|" + userId, allowance, RECIPIENT_WINDOW, Instant.now())
                .allowed();
    }

    /**
     * Refuses a batch aimed at more users than one call may reach.
     *
     * @throws NotificationException {@code RATE_LIMITED}, which the SDK marks retryable — a scheduled
     *                               sender is expected to hold the batch and split it, not drop it
     */
    public void checkBatch(String pluginId, int recipientCount) throws NotificationException {
        if (recipientCount > properties.maxBatch()) {
            throw new NotificationException(NotificationException.Reason.RATE_LIMITED,
                    "A notification may reach at most " + properties.maxBatch() + " users in one call");
        }
    }
}
