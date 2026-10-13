// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Core's counts in memory, written to the database once a minute (ARCHITECTURE §19). A burst of views is a
 * handful of increments here rather than a write each, and the additive upsert lets several core instances
 * flush into the same rows.
 */
@Component
public class MetricsAggregator {

    private static final Logger log = LoggerFactory.getLogger(MetricsAggregator.class);

    private final MetricsStore store;
    private final Clock clock;
    private final ConcurrentHashMap<MetricKey, LongAdder> pending = new ConcurrentHashMap<>();

    public MetricsAggregator(MetricsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Counts one event in the current UTC hour. */
    public void count(String kind, String subject, int milestone) {
        MetricKey key = new MetricKey(clock.instant().truncatedTo(ChronoUnit.HOURS), "core", kind, subject,
                milestone);
        pending.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /** Writes what has been counted since the last flush. */
    @Scheduled(initialDelay = 60, fixedDelay = 60, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<MetricKey, Long> batch = new HashMap<>();
        for (MetricKey key : pending.keySet()) {
            LongAdder adder = pending.remove(key);
            if (adder != null) {
                long n = adder.sum();
                if (n > 0) {
                    batch.merge(key, n, Long::sum);
                }
            }
        }
        try {
            store.add(batch);
        } catch (RuntimeException e) {
            // Put them back rather than lose a minute of counts to a database hiccup.
            batch.forEach((k, n) -> pending.computeIfAbsent(k, x -> new LongAdder()).add(n));
            log.warn("Counts not written, kept for the next flush: {}", e.getMessage());
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    /** Pending counters, for tests. */
    int pendingKeys() {
        return pending.size();
    }
}
