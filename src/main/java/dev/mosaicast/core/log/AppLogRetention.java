// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the operational log bounded (ARCHITECTURE §13): prunes by age, then by row cap. Runs daily under
 * ShedLock like the feed poll, so multiple instances do not each delete the same rows.
 */
@Component
public class AppLogRetention {

    private static final Logger log = LoggerFactory.getLogger(AppLogRetention.class);

    private final AppLogService logs;

    public AppLogRetention(AppLogService logs) {
        this.logs = logs;
    }

    @Scheduled(fixedDelayString = "${mosaicast.log.prune-interval-ms:86400000}", initialDelay = 60_000)
    @SchedulerLock(name = "app-log-prune", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void prune() {
        try {
            int removed = logs.prune();
            if (removed > 0) {
                log.info("Pruned {} log entries", removed);
            }
        } catch (RuntimeException e) {
            // Retention failing is not worth a stack trace on every tick, and must never kill the scheduler.
            log.warn("Could not prune the log: {}", e.getMessage());
        }
    }
}
