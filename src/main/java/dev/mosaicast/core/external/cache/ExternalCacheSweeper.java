// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.cache;

import dev.mosaicast.core.external.ExternalProperties;
import dev.mosaicast.core.external.pipeline.ExternalCallPipeline;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the result cache from growing without bound (ARCHITECTURE §12.7).
 *
 * <p>Daily and under ShedLock, so several instances do not all delete the same rows. Two passes: expired
 * entries first, then the least-recently-read down to the configured bound.
 *
 * <p>Deliberately <em>not</em> triggered by a settings change. The config fingerprint is part of the cache
 * key, so entries written under an old base URL are already unreachable; deleting them eagerly would throw
 * away paid work in the case where an admin changes a setting and changes it back.
 */
@Component
public class ExternalCacheSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExternalCacheSweeper.class);

    private final ExternalCacheStore store;
    private final ExternalCallPipeline pipeline;
    private final ExternalProperties properties;

    public ExternalCacheSweeper(ExternalCacheStore store, ExternalCallPipeline pipeline,
                                ExternalProperties properties) {
        this.store = store;
        this.pipeline = pipeline;
        this.properties = properties;
    }

    @Scheduled(cron = "${mosaicast.external.cache-sweep-cron:0 20 3 * * *}")
    @SchedulerLock(name = "external-cache-sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void sweep() {
        int expired = store.deleteExpired();
        int trimmed = store.trimTo(properties.cacheMaxEntriesOrDefault());
        if (expired > 0 || trimmed > 0) {
            log.info("External cache swept: {} expired, {} trimmed", expired, trimmed);
        }
    }

    /**
     * Drops this instance's own expired rate-limit windows.
     *
     * <p>Its own schedule, with no {@link SchedulerLock}, and that is the whole point. It used to sit inside
     * {@link #sweep()}, under a lock, beneath a comment claiming it was "not under the lock's protection and
     * does not need to be" — the second half was right and the first was not. The windows are in memory and
     * per instance, so a lock that lets exactly one instance run the sweep means exactly one instance ever
     * drops its counters; every other one accumulates a map entry per client key until it restarts.
     *
     * <p>The cache sweep above genuinely needs the lock: it deletes shared rows, and several instances doing
     * that at once is wasted work. These two were one method because they ran at the same time, which is not
     * a reason to share a lock.
     */
    @Scheduled(fixedDelayString = "${mosaicast.external.window-sweep-delay-ms:600000}")
    public void evictExpiredRateLimitWindows() {
        pipeline.evictExpiredWindows();
    }
}
