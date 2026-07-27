// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the operational log (ARCHITECTURE §13). Defaults are chosen so an operator never has to
 * touch them: capture what an operator would act on, keep it for a month, and stay bounded.
 *
 * @param captureLevel        lowest severity captured from core's own loggers ({@code ERROR}, {@code WARN},
 *                            {@code INFO}, {@code DEBUG}); everything below is left to stdout
 * @param retentionDays       age after which entries are pruned
 * @param maxRows             hard cap on retained rows, enforced after the age prune
 * @param queueSize           in-memory hand-off buffer; a full queue drops entries rather than blocking the
 *                            thread that logged (a log write must never slow down or fail a request)
 * @param pluginRatePerMinute how many entries one plugin may report per minute before being throttled
 */
@ConfigurationProperties(prefix = "mosaicast.log")
public record AppLogProperties(
        @DefaultValue("WARN") String captureLevel,
        @DefaultValue("30") int retentionDays,
        @DefaultValue("100000") long maxRows,
        @DefaultValue("1000") int queueSize,
        @DefaultValue("60") int pluginRatePerMinute) {

    /** The parsed capture threshold, falling back to {@code WARN} on an unusable value. */
    public AppLogLevel threshold() {
        return AppLogLevel.parse(captureLevel).orElse(AppLogLevel.WARN);
    }
}
