// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.log.AppLogProperties;
import dev.mosaicast.core.plugin.PluginLogRateLimiter.Decision;
import org.junit.jupiter.api.Test;

/**
 * The log rate limit (ARCHITECTURE §7.8): a plugin stuck in a render loop must not be able to fill the table,
 * and the throttling itself has to be visible exactly once rather than silently or endlessly.
 */
class PluginLogRateLimiterTest {

    private final PluginLogRateLimiter limiter =
            new PluginLogRateLimiter(new AppLogProperties("WARN", 30, 100_000, 1_000, 2));

    @Test
    void entriesWithinTheBudgetAreAllowed() {
        assertThat(limiter.check("acme")).isEqualTo(Decision.ALLOWED);
        assertThat(limiter.check("acme")).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void theFirstRejectionIsReportedAndTheRestAreSilent() {
        limiter.check("acme");
        limiter.check("acme");

        assertThat(limiter.check("acme")).isEqualTo(Decision.THROTTLED_FIRST);
        assertThat(limiter.check("acme")).isEqualTo(Decision.THROTTLED);
        assertThat(limiter.check("acme")).isEqualTo(Decision.THROTTLED);
    }

    @Test
    void budgetsArePerPlugin() {
        limiter.check("acme");
        limiter.check("acme");
        limiter.check("acme");

        // One noisy plugin must not consume another's allowance.
        assertThat(limiter.check("other")).isEqualTo(Decision.ALLOWED);
    }
}
