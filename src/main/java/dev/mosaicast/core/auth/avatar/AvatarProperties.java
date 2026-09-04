// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bounds on the avatar proxy (ARCHITECTURE §8.7).
 *
 * @param sizePx        the pixel size requested from providers that support asking
 * @param maxImageBytes the most one picture may weigh; anything larger is refused rather than truncated
 * @param cacheBytes    the ceiling on the whole cache, in bytes — <strong>not</strong> an entry count
 * @param ttl           how long a fetched picture is served before it is fetched again
 * @param failureTtl    how long a failure is remembered
 * @param timeout       the wall-clock budget for one fetch
 */
@ConfigurationProperties(prefix = "mosaicast.avatar")
public record AvatarProperties(
        Integer sizePx,
        Integer maxImageBytes,
        Long cacheBytes,
        Duration ttl,
        Duration failureTtl,
        Duration timeout) {

    public AvatarProperties {
        sizePx = sizePx == null ? 128 : sizePx;
        maxImageBytes = maxImageBytes == null ? 256 * 1024 : maxImageBytes;
        // Bounded by total bytes because an entry count does not bound anything that matters: 500 entries
        // of unknown size is not a limit, it is a hope.
        cacheBytes = cacheBytes == null ? 32L * 1024 * 1024 : cacheBytes;
        ttl = ttl == null ? Duration.ofMinutes(15) : ttl;
        // Shorter, but never zero. Without a negative cache a single 404 behind a leaderboard becomes one
        // outbound request per page view.
        failureTtl = failureTtl == null ? Duration.ofMinutes(1) : failureTtl;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
