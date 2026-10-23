// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.web.FixedWindowRateLimiter;
import dev.mosaicast.core.web.TooManyRequestsException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The host's budgets for the realtime surfaces (platformApi 0.20.0, ARCHITECTURE §7.9): actions per user and
 * per plugin, subscribe requests per user.
 *
 * <p>Host-owned for the reason notification limits are (§17.1): a limit a plugin enforces is a limit a plugin
 * can drop. Per instance, like every counter here until Redis arrives (§13). A refusal carries the time until
 * the window turns over, which the browser backs off on.
 */
@Component
public class PluginRealtimeRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final PluginRealtimeProperties properties;
    private final FixedWindowRateLimiter perUser = new FixedWindowRateLimiter();
    private final FixedWindowRateLimiter perPlugin = new FixedWindowRateLimiter();
    private final FixedWindowRateLimiter subscribes = new FixedWindowRateLimiter();

    public PluginRealtimeRateLimiter(PluginRealtimeProperties properties) {
        this.properties = properties;
    }

    /** Spends one action call, or throws a 429 naming when to retry. The user budget first: it is the usual one. */
    void spendAction(PluginManifest manifest, UUID userId, Instant now) {
        refuseUnless(perUser.check(manifest.id() + "|" + userId,
                properties.actionsPerUserPerMinute(manifest), WINDOW, now),
                "Too many actions; try again shortly.");
        refuseUnless(perPlugin.check(manifest.id(), properties.actionsPerPluginPerMinute(manifest), WINDOW, now),
                "This plugin is answering too many actions; try again shortly.");
    }

    /** Spends one subscribe request. */
    void spendSubscribe(UUID userId, Instant now) {
        refuseUnless(subscribes.check(userId.toString(), properties.subscribesPerUserPerMinute(), WINDOW, now),
                "Too many subscriptions; try again shortly.");
    }

    private static void refuseUnless(FixedWindowRateLimiter.Decision decision, String message) {
        if (!decision.allowed()) {
            throw new TooManyRequestsException(message, decision.retryAfter());
        }
    }

    @Scheduled(fixedDelay = 300_000)
    void evictExpiredWindows() {
        Instant now = Instant.now();
        perUser.evictExpired(WINDOW, now);
        perPlugin.evictExpired(WINDOW, now);
        subscribes.evictExpired(WINDOW, now);
    }
}
