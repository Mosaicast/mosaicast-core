// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes export archives past their retention, and fails exports a restart interrupted (ARCHITECTURE §12.8.1).
 *
 * <p>An archive is everything about one person, so "available for seven days" has to mean deleted after
 * seven days, not merely unlinked. {@code @SchedulerLock} for the reason every sweep here has one: two
 * instances must not delete the same blob at once.
 */
@Component
public class ExportRetention {

    private static final Logger log = LoggerFactory.getLogger(ExportRetention.class);

    private final ExportService exports;

    public ExportRetention(ExportService exports) {
        this.exports = exports;
    }

    @Scheduled(fixedDelayString = "${mosaicast.export.sweep-interval-ms:3600000}", initialDelay = 60_000)
    @SchedulerLock(name = "user-export-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        int changed = exports.sweep();
        if (changed > 0) {
            log.info("Export sweep: {} export(s) expired or marked interrupted", changed);
        }
    }
}
