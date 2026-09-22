// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Basic rate limiting on auth endpoints and uploads (ARCHITECTURE §13).
 *
 * <p>Two buckets, because the two abuses look nothing alike. Login attempts are small and fast and a flood
 * of them is credential stuffing, so the budget is per minute and low. Uploads are large and slow and a
 * flood of them is a disk and bandwidth problem, so the budget is lower still. Sharing one number between
 * them would mean setting it wrong for at least one.
 *
 * <p>Defaults are chosen to sit far above human use and far below automated use: a person who fat-fingers a
 * login five times in a row is unaffected, a script trying thousands is not.
 *
 * @param enabled       whether to enforce at all; off makes every check pass (an escape hatch for a load
 *                      test or an operator who front-ends the app with their own limiter)
 * @param authLimit     login/token attempts allowed per client per {@code authWindow}
 * @param authWindow    how long the auth window lasts
 * @param uploadLimit   uploads allowed per client per {@code uploadWindow}
 * @param uploadWindow  how long the upload window lasts
 * @param outboundLimit server-side fetches of an operator-supplied URL allowed per client per
 *                      {@code outboundWindow} — the feed add/preview/refresh surface
 * @param outboundWindow how long the outbound window lasts
 * @param searchLimit   anonymous searches allowed per client per {@code searchWindow}
 * @param searchWindow  how long the search window lasts
 */
@ConfigurationProperties(prefix = "mosaicast.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        Integer authLimit,
        Duration authWindow,
        Integer uploadLimit,
        Duration uploadWindow,
        Integer outboundLimit,
        Duration outboundWindow,
        Integer searchLimit,
        Duration searchWindow) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public int authLimitOrDefault() {
        return authLimit == null ? 20 : authLimit;
    }

    public Duration authWindowOrDefault() {
        return authWindow == null ? Duration.ofMinutes(1) : authWindow;
    }

    public int uploadLimitOrDefault() {
        return uploadLimit == null ? 10 : uploadLimit;
    }

    public Duration uploadWindowOrDefault() {
        return uploadWindow == null ? Duration.ofMinutes(1) : uploadWindow;
    }

    /**
     * Sized for a person managing feeds by hand, not for a script: the whole /api/admin/feeds surface shares
     * this budget (add, preview, refresh, planned episodes), which no human workflow comes near.
     */
    public int outboundLimitOrDefault() {
        return outboundLimit == null ? 30 : outboundLimit;
    }

    public Duration outboundWindowOrDefault() {
        return outboundWindow == null ? Duration.ofMinutes(1) : outboundWindow;
    }

    /**
     * Generous on purpose. Search is a legitimate, repeated thing for one visitor to do — typing, correcting,
     * trying another word — so this is sized to be invisible to a person and to still put a ceiling on a
     * client that has stopped being one.
     */
    public int searchLimitOrDefault() {
        return searchLimit == null ? 60 : searchLimit;
    }

    public Duration searchWindowOrDefault() {
        return searchWindow == null ? Duration.ofMinutes(1) : searchWindow;
    }

    /** The longest window in play — what an eviction sweep has to outlive. */
    public Duration longestWindow() {
        Duration longest = authWindowOrDefault();
        for (Duration window : new Duration[] {uploadWindowOrDefault(), outboundWindowOrDefault()}) {
            if (window.compareTo(longest) > 0) {
                longest = window;
            }
        }
        return longest;
    }
}
