// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One requested data export (ARCHITECTURE §12.8.1): its state, and where the archive is once it is built.
 * The archive is never readable by anyone but {@link #getUserId()} — the admin view reads this row, never the
 * blob it points at.
 */
@Entity
@Table(name = "user_data_export")
public class UserDataExport {

    /** Where an export stands. */
    public enum Status {
        /** Being built. */
        RUNNING,
        /** Built, downloadable until {@code expiresAt}. */
        READY,
        /** Building it failed; nothing was stored. */
        FAILED,
        /** Was ready; the archive has been deleted after its retention. */
        EXPIRED
    }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "blob_id")
    private UUID blobId;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column
    private String error;

    protected UserDataExport() {
        // for JPA
    }

    /** A freshly requested export, running. */
    public static UserDataExport requested(UUID userId, Instant now) {
        UserDataExport export = new UserDataExport();
        export.id = UUID.randomUUID();
        export.userId = userId;
        export.status = Status.RUNNING;
        export.requestedAt = now;
        return export;
    }

    /** The archive is stored and downloadable until {@code expiresAt}. */
    public void ready(UUID blobId, long sizeBytes, Instant now, Instant expiresAt) {
        this.status = Status.READY;
        this.blobId = blobId;
        this.sizeBytes = sizeBytes;
        this.finishedAt = now;
        this.expiresAt = expiresAt;
    }

    /** Building it failed; {@code reason} is for the admin view, never shown as the person's data. */
    public void failed(String reason, Instant now) {
        this.status = Status.FAILED;
        this.error = reason;
        this.finishedAt = now;
    }

    /** The archive was deleted after its retention. */
    public void expired() {
        this.status = Status.EXPIRED;
        this.blobId = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getBlobId() {
        return blobId;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getError() {
        return error;
    }
}
