// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The operator's side of plugin scheduling ({@code mosaicast.plugin-schedule.*}, ARCHITECTURE §7.4).
 *
 * <p>A plugin asks for a period, and since {@code platformApi} 0.15.0 it may ask again before every tick —
 * usually by reading a number an operator typed into the admin form. That makes the ask a number no one
 * reviewed: a typo, or a plugin that divides where it meant to multiply, turns into a tick every second,
 * and every tick takes a ShedLock row and a database round-trip on every instance.
 *
 * <p>So the period is a <em>request</em>, the same way the manifest's other numbers are, and this is the
 * floor it is clamped to. The default is deliberately dull rather than protective: ten seconds is far below
 * anything a podcast host legitimately needs (the plugins that ship ingest on the order of a minute) while
 * being far above the rate at which a mistake hurts. An operator who really does want a faster tick lowers
 * it here, which is the property of a floor that lives in configuration rather than in code.
 *
 * @param minPeriod the shortest period a plugin may actually run at; shorter asks are clamped and logged
 */
@ConfigurationProperties(prefix = "mosaicast.plugin-schedule")
public record PluginScheduleProperties(@DefaultValue("10s") Duration minPeriod) {

    /** The floor, never null and never non-positive — a misconfigured floor must not disable scheduling. */
    public Duration minPeriodOrDefault() {
        return minPeriod == null || minPeriod.isZero() || minPeriod.isNegative() ? Duration.ofSeconds(10) : minPeriod;
    }
}
