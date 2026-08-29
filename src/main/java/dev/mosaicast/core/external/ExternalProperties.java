// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the external-service call pipeline (ARCHITECTURE §12.7).
 *
 * <p>Defaults are chosen for the case that actually exists: a self-hosted translator on the same network,
 * called by an admin who pressed a button. An operator on a paid API with different economics can move them.
 *
 * @param maxConcurrent how many calls to one kind may be in flight; the bulkhead
 * @param maxQueueWait  how long to wait for a permit before answering "we are full". Short on purpose — the
 *                      caller is a servlet thread, so waiting is expensive
 * @param callTimeout   wall-clock ceiling on one call, everything included
 * @param cacheTtl      how long a result stays good; zero means until something purges it
 * @param cacheMaxEntries the bound the sweeper trims to, least-recently-read first
 */
@ConfigurationProperties(prefix = "mosaicast.external")
public record ExternalProperties(
        Integer maxConcurrent,
        Duration maxQueueWait,
        Duration callTimeout,
        Duration cacheTtl,
        Long cacheMaxEntries) {

    public int maxConcurrentOrDefault() {
        return maxConcurrent == null || maxConcurrent < 1 ? 4 : maxConcurrent;
    }

    public Duration maxQueueWaitOrDefault() {
        return maxQueueWait == null ? Duration.ofSeconds(5) : maxQueueWait;
    }

    public Duration callTimeoutOrDefault() {
        return callTimeout == null ? Duration.ofSeconds(30) : callTimeout;
    }

    /** Ninety days: long enough that a translated page survives a release, short enough to age out. */
    public Duration cacheTtlOrDefault() {
        return cacheTtl == null ? Duration.ofDays(90) : cacheTtl;
    }

    public long cacheMaxEntriesOrDefault() {
        return cacheMaxEntries == null || cacheMaxEntries < 1 ? 50_000 : cacheMaxEntries;
    }
}
