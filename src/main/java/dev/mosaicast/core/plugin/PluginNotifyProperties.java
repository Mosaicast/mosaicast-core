// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What a plugin may send, and how much (ARCHITECTURE §17.1).
 *
 * <p>Two kinds of number, and the difference is the same one blob quotas draw (§11.1):
 * {@code default-} is what a plugin gets when its manifest expresses no opinion, and {@code hard-} is the
 * ceiling no manifest can reach past. Without the second, {@code perUserPerDay} in a manifest would be a
 * plugin setting its own limit — which is not a limit.
 *
 * @param defaultPerUserPerDay what a plugin gets when its manifest names no number
 * @param hardPerUserPerDay    the most any plugin may send one user per day, whatever it asked for
 * @param maxBatch             the most recipients one call may reach
 */
@ConfigurationProperties(prefix = "mosaicast.plugin-notifications")
public record PluginNotifyProperties(
        @DefaultValue("5") int defaultPerUserPerDay,
        @DefaultValue("20") int hardPerUserPerDay,
        @DefaultValue("200") int maxBatch) {
}
