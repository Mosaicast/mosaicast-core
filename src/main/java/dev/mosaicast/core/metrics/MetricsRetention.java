// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Folds hour counts older than {@link MetricsStore#HOURLY_DAYS} days into day counts, nightly (ARCHITECTURE
 * §19). Under a ShedLock so several instances do not fold the same rows twice.
 */
@Component
public class MetricsRetention {

    private static final Logger log = LoggerFactory.getLogger(MetricsRetention.class);

    private final MetricsStore store;
    private final Clock clock;

    public MetricsRetention(MetricsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    @SchedulerLock(name = "metrics-retention", lockAtMostFor = "PT30M")
    public void fold() {
        int folded = store.foldOldHours(clock.instant());
        if (folded > 0) {
            log.info("Folded {} hourly count rows older than {} days into daily rows", folded,
                    MetricsStore.HOURLY_DAYS);
        }
    }
}
