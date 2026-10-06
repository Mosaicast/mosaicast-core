// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import dev.mosaicast.plugin.api.UserExport;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bounds on the data export (ARCHITECTURE §12.8.1).
 *
 * <p>The two plugin limits are the SDK's ({@link UserExport#MAX_BYTES}, {@link UserExport#TIMEOUT}) and can
 * only be <em>lowered</em> here: a plugin is promised at least that much room and time, and an install that
 * granted more would make a plugin's behaviour depend on where it runs.
 *
 * @param minInterval     how long an account waits between two exports; a failed one does not count
 * @param retention       how long a finished archive stays downloadable before it is deleted
 * @param pluginMaxBytes  the most one plugin's part may be; clamped to {@link UserExport#MAX_BYTES}
 * @param pluginTimeout   how long one plugin is given; clamped to {@link UserExport#TIMEOUT}
 */
@ConfigurationProperties(prefix = "mosaicast.export")
public record ExportProperties(Duration minInterval, Duration retention, Long pluginMaxBytes,
                               Duration pluginTimeout) {

    public ExportProperties {
        minInterval = minInterval == null ? Duration.ofDays(1) : minInterval;
        retention = retention == null ? Duration.ofDays(7) : retention;
        pluginMaxBytes = pluginMaxBytes == null || pluginMaxBytes <= 0 || pluginMaxBytes > UserExport.MAX_BYTES
                ? UserExport.MAX_BYTES : pluginMaxBytes;
        pluginTimeout = pluginTimeout == null || pluginTimeout.isNegative() || pluginTimeout.isZero()
                || pluginTimeout.compareTo(UserExport.TIMEOUT) > 0 ? UserExport.TIMEOUT : pluginTimeout;
    }
}
