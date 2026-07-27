// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the host's operational log (ARCHITECTURE §13).
 *
 * <p><strong>Writes never block the caller.</strong> Entries are handed to a bounded queue and persisted by a
 * single daemon thread, so a slow or broken database degrades logging rather than the request that produced
 * the log line. A full queue drops entries and counts the drops instead of applying back-pressure — losing
 * diagnostics is always better than stalling the site that produced them.
 *
 * <p>Two failure modes are guarded explicitly, because getting them wrong turns the feature that explains
 * outages into one that causes them:
 * <ul>
 *   <li><strong>Reentrancy.</strong> A failure inside the writer must not be logged through SLF4J, or the
 *       appender would feed it back into this queue. The writer thread is identifiable
 *       ({@link #isWriterThread()}) and the appender ignores events raised on it; the writer itself reports
 *       problems to {@code stderr}, never to a logger.</li>
 *   <li><strong>Transaction independence.</strong> Persisting happens on the writer thread, outside any
 *       caller's transaction, so an entry describing a failing transaction is not rolled back with it.</li>
 * </ul>
 */
@Service
public class AppLogService {

    /** How many entries one database round-trip may carry. */
    private static final int BATCH = 100;

    /** At most one "entries were dropped" notice per this interval, however many were lost. */
    private static final long DROP_NOTICE_INTERVAL_SECONDS = 60;

    private final AppLogRepository repository;
    private final AppLogProperties properties;
    private final BlockingQueue<AppLogEntry> queue;

    private final AtomicLong dropped = new AtomicLong();
    private volatile Instant lastDropNotice = Instant.EPOCH;
    private volatile Thread writer;
    private volatile boolean running;

    public AppLogService(AppLogRepository repository, AppLogProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(Math.max(16, properties.queueSize()));
    }

    @PostConstruct
    void start() {
        running = true;
        writer = new Thread(this::drainForever, "app-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
        }
        // Best-effort flush so entries from a clean shutdown are not lost.
        drainOnce();
    }

    /** Whether the calling thread is the log writer — the appender's guard against feeding itself. */
    public boolean isWriterThread() {
        return Thread.currentThread() == writer;
    }

    /** Queues an entry. Never throws, never blocks; a full queue counts a drop and returns. */
    public void record(AppLogLevel level, String subsystem, String source, String pluginId,
                       String message, String detail, JsonNode context) {
        if (level == null || message == null) {
            return;
        }
        AppLogEntry entry = new AppLogEntry(
                Instant.now(), level, subsystem == null ? "core" : subsystem, source, pluginId,
                message, detail, context);
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    /** Convenience for callers that have no detail or structured context. */
    public void record(AppLogLevel level, String subsystem, String source, String message) {
        record(level, subsystem, source, null, message, null, null);
    }

    /**
     * One filtered page for the viewer, newest first. Null or blank filters mean "no restriction"; they are
     * translated to the sentinels the query expects (see {@link AppLogRepository#search}).
     */
    @Transactional(readOnly = true)
    public Page<AppLogEntry> search(String level, String subsystem, String pluginId, Instant since,
                                    String text, Pageable pageable) {
        return repository.search(
                orEmpty(level), orEmpty(subsystem), orEmpty(pluginId),
                since == null ? Instant.EPOCH : since,
                orEmpty(text), pageable);
    }

    /** Distinct subsystems and plugin ids present in the log, for the filter dropdowns. */
    @Transactional(readOnly = true)
    public Facets facets() {
        return new Facets(repository.distinctSubsystems(), repository.distinctPluginIds());
    }

    /** What the filter dropdowns offer. */
    public record Facets(List<String> subsystems, List<String> pluginIds) {
    }

    /** ERROR/WARN counts per subsystem since a cut-off — the health card. */
    @Transactional(readOnly = true)
    public List<SubsystemCount> countsSince(Instant since) {
        return repository.countBySubsystemSince(since).stream()
                .map(row -> new SubsystemCount((String) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();
    }

    /** One (subsystem, level) tally. */
    public record SubsystemCount(String subsystem, String level, long count) {
    }

    /**
     * Applies retention: first by age, then by row cap. Returns how many rows were removed. Called on a
     * schedule; safe to call at any time.
     */
    @Transactional
    public int prune() {
        int removed = repository.deleteOlderThan(
                Instant.now().minus(Math.max(1, properties.retentionDays()), ChronoUnit.DAYS));
        removed += repository.trimToMaxRows(Math.max(1_000, properties.maxRows()));
        return removed;
    }

    private void drainForever() {
        while (running) {
            try {
                AppLogEntry first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    noticeDropsIfDue();
                    continue;
                }
                List<AppLogEntry> batch = new ArrayList<>(BATCH);
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                persist(batch);
                noticeDropsIfDue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Deliberately not a logger: that would come straight back through the appender.
                System.err.println("[app-log] writer failed: " + t);
            }
        }
    }

    private void drainOnce() {
        List<AppLogEntry> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            persist(batch);
        }
    }

    private void persist(List<AppLogEntry> batch) {
        try {
            repository.saveAll(batch);
        } catch (Throwable t) {
            System.err.println("[app-log] could not persist " + batch.size() + " entries: " + t);
        }
    }

    /**
     * Surfaces dropped entries as one entry of their own, at most once a minute — otherwise a queue overflow
     * would be the one failure the log cannot mention.
     */
    private void noticeDropsIfDue() {
        long lost = dropped.get();
        if (lost == 0) {
            return;
        }
        Instant now = Instant.now();
        if (now.isBefore(lastDropNotice.plusSeconds(DROP_NOTICE_INTERVAL_SECONDS))) {
            return;
        }
        lastDropNotice = now;
        dropped.addAndGet(-lost);
        persist(List.of(new AppLogEntry(now, AppLogLevel.WARN, "log", "AppLogService", null,
                lost + " log entries were dropped because the write queue was full", null, null)));
    }

    private static String orEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
