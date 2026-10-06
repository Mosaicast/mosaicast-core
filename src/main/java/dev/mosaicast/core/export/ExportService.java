// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import dev.mosaicast.core.blob.BlobContent;
import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStore;
import dev.mosaicast.core.notification.NotificationKind;
import dev.mosaicast.core.notification.NotificationService;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.web.TooManyRequestsException;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The GDPR data export (ARCHITECTURE §12.8.1): a person asks, the archive is built off the request, they are
 * told when it is ready, and it is theirs to download for a limited time.
 *
 * <p><strong>Only the owner ever reads an archive.</strong> {@link #download} checks the caller is the export's
 * user, and the admin view ({@link #recent}) reads rows and outcomes, never the blob — the archive is
 * everything about one person, and an operator who can see that it failed for a plugin needs nothing more.
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** The blob namespace archives live in; {@code §11} routes it like any other. */
    static final String NAMESPACE = "export";

    /** A run takes minutes at most; one still marked running after this was interrupted by a restart. */
    static final Duration STALE_AFTER = Duration.ofHours(1);

    private final UserDataExportRepository exports;
    private final UserDataExportPartRepository parts;
    private final ExportArchive archive;
    private final BlobStore blobs;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final ExportProperties properties;

    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();

    public ExportService(UserDataExportRepository exports, UserDataExportPartRepository parts,
                         ExportArchive archive, BlobStore blobs, NotificationService notifications,
                         ApplicationEventPublisher events, ExportProperties properties) {
        this.exports = exports;
        this.parts = parts;
        this.archive = archive;
        this.blobs = blobs;
        this.notifications = notifications;
        this.events = events;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        jobs.shutdownNow();
    }

    /**
     * One export as its owner sees it.
     *
     * @param id          the export
     * @param status      {@code running | ready | failed | expired}
     * @param requestedAt when it was asked for
     * @param finishedAt  when it was built or failed; null while running
     * @param expiresAt   until when the archive may be downloaded; null unless ready
     * @param sizeBytes   the archive's size; null unless ready
     * @param nextAllowedAt when this account may ask again
     */
    public record ExportView(UUID id, String status, Instant requestedAt, Instant finishedAt, Instant expiresAt,
                             Long sizeBytes, Instant nextAllowedAt) {
    }

    /**
     * Starts an export — the receipt, not the archive.
     *
     * @throws TooManyRequestsException if this account exported within the interval; a failed export does not
     *                                  count, so a person is never locked out by the site's own failure
     */
    @Transactional
    public ExportView request(UUID userId) {
        Instant now = Instant.now();
        Optional<UserDataExport> latest =
                exports.findFirstByUserIdAndStatusNotOrderByRequestedAtDesc(userId, UserDataExport.Status.FAILED);
        if (latest.isPresent() && latest.get().getRequestedAt().plus(properties.minInterval()).isAfter(now)) {
            throw new TooManyRequestsException("An export was requested at " + latest.get().getRequestedAt()
                    + "; the next one is possible from " + latest.get().getRequestedAt().plus(properties.minInterval())
                    + ".");
        }
        UserDataExport export = exports.save(UserDataExport.requested(userId, now));
        events.publishEvent(new ExportRequestedEvent(export.getId()));
        log.info("Data export {} requested by {}", export.getId(), userId);
        return view(export);
    }

    /** The newest export of an account, if it ever asked for one. */
    @Transactional(readOnly = true)
    public Optional<ExportView> latest(UUID userId) {
        return exports.findFirstByUserIdOrderByRequestedAtDesc(userId).map(this::view);
    }

    /** Builds the archive once the request committed, off the request thread. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(ExportRequestedEvent event) {
        jobs.execute(() -> build(event.exportId()));
    }

    /** Builds, stores and announces one export; any failure is recorded on the row, never thrown away. */
    void build(UUID exportId) {
        UserDataExport export = exports.findById(exportId).orElse(null);
        if (export == null || export.getStatus() != UserDataExport.Status.RUNNING) {
            return;
        }
        Path file = null;
        try {
            file = archive.build(export);
            long size = Files.size(file);
            BlobRef stored;
            try (InputStream in = Files.newInputStream(file)) {
                stored = blobs.put(NAMESPACE, export.getId().toString(), in, "application/zip",
                        filename(export), export.getUserId());
            }
            Instant now = Instant.now();
            export.ready(stored.id(), size, now, now.plus(properties.retention()));
            exports.save(export);
            notifications.system(export.getUserId(), NotificationKind.EXPORT_READY,
                    Map.of("expiresAt", export.getExpiresAt().toString()), "/account#export");
            log.info("Data export {} ready: {} bytes", exportId, size);
        } catch (Exception e) {
            log.warn("Data export {} failed", exportId, e);
            export.failed(e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now());
            exports.save(export);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException e) {
                    log.warn("Could not delete the temporary export file {}", file, e);
                }
            }
        }
    }

    /** What a download serves: the archive, and the name to save it under. */
    public record Download(BlobContent content, String filename) {
    }

    /**
     * The archive of one export, for its owner only.
     *
     * @throws NotFoundException for anyone else, and for an export that is not (or no longer) ready — one
     *                           answer, so a caller cannot probe which export ids exist
     */
    @Transactional(readOnly = true)
    public Download download(UUID userId, UUID exportId) {
        UserDataExport export = exports.findById(exportId)
                .filter(e -> e.getUserId().equals(userId))
                .filter(e -> e.getStatus() == UserDataExport.Status.READY && e.getBlobId() != null)
                .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new NotFoundException("No downloadable export " + exportId));
        return new Download(blobs.get(new BlobRef(export.getBlobId(), NAMESPACE)), filename(export));
    }

    /**
     * One export as the admin view shows it: the job and each plugin's outcome, never the archive.
     *
     * @param parts plugin id → outcome (wire name), with its detail
     */
    public record AdminExportView(UUID id, UUID userId, String status, Instant requestedAt, Instant finishedAt,
                                  Instant expiresAt, Long sizeBytes, String error, List<AdminPart> parts) {
    }

    /** One plugin's outcome in the admin view. */
    public record AdminPart(String pluginId, String outcome, String detail) {
    }

    /** The newest exports, for admin. */
    @Transactional(readOnly = true)
    public List<AdminExportView> recent(int limit) {
        List<UserDataExport> rows = exports.findAllByOrderByRequestedAtDesc(PageRequest.of(0, limit));
        Map<UUID, List<AdminPart>> byExport = new java.util.HashMap<>();
        parts.findByExportIdIn(rows.stream().map(UserDataExport::getId).toList()).forEach(part ->
                byExport.computeIfAbsent(part.getExportId(), id -> new java.util.ArrayList<>())
                        .add(new AdminPart(part.getPluginId(), part.getOutcome().wireName(), part.getDetail())));
        return rows.stream().map(e -> new AdminExportView(e.getId(), e.getUserId(),
                e.getStatus().name().toLowerCase(java.util.Locale.ROOT), e.getRequestedAt(), e.getFinishedAt(),
                e.getExpiresAt(), e.getSizeBytes(), e.getError(),
                byExport.getOrDefault(e.getId(), List.of()).stream()
                        .sorted(java.util.Comparator.comparing(AdminPart::pluginId)).toList())).toList();
    }

    /**
     * Deletes every export of an account, archives first — part of erasure (§12.8.1): an archive of a deleted
     * account would be that account's data outliving it.
     *
     * @return how many exports went
     */
    @Transactional
    public int eraseFor(UUID userId) {
        List<UserDataExport> owned = exports.findByUserId(userId);
        for (UserDataExport export : owned) {
            if (export.getBlobId() != null) {
                blobs.delete(new BlobRef(export.getBlobId(), NAMESPACE));
            }
        }
        exports.deleteAll(owned);
        return owned.size();
    }

    /**
     * Deletes archives past their retention and fails exports a restart interrupted.
     *
     * @return how many rows changed
     */
    @Transactional
    public int sweep() {
        Instant now = Instant.now();
        int changed = 0;
        for (UserDataExport export : exports.findByStatusAndExpiresAtBefore(UserDataExport.Status.READY, now)) {
            if (export.getBlobId() != null) {
                blobs.delete(new BlobRef(export.getBlobId(), NAMESPACE));
            }
            export.expired();
            exports.save(export);
            changed++;
        }
        for (UserDataExport export : exports.findByStatusAndRequestedAtBefore(
                UserDataExport.Status.RUNNING, now.minus(STALE_AFTER))) {
            export.failed("interrupted before it finished", now);
            exports.save(export);
            changed++;
        }
        return changed;
    }

    private ExportView view(UserDataExport export) {
        Instant next = export.getStatus() == UserDataExport.Status.FAILED
                ? export.getFinishedAt() : export.getRequestedAt().plus(properties.minInterval());
        return new ExportView(export.getId(), export.getStatus().name().toLowerCase(java.util.Locale.ROOT),
                export.getRequestedAt(), export.getFinishedAt(), export.getExpiresAt(), export.getSizeBytes(), next);
    }

    private static String filename(UserDataExport export) {
        return "export-" + export.getRequestedAt().atOffset(ZoneOffset.UTC).toLocalDate() + ".zip";
    }
}
