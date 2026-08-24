// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.erasure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One plugin's part of one account deletion (ARCHITECTURE §12, SDK {@code UserDataHandler}).
 *
 * <p>Written before the plugin is asked, not after it answers. A deletion that ran, hit an exception and
 * logged it would leave the person told their data was gone while it was still there — so the record exists
 * first, and only a successful handler marks it {@link Status#DONE}.
 */
@Entity
@Table(name = "user_data_erasure")
public class UserDataErasure {

    /** Where one plugin's erasure got to. */
    public enum Status {
        /** Recorded, not yet carried out — the plugin was disabled, or a retry is due. */
        PENDING,
        /** The handler threw. Retried; the last message is kept so an operator can see why. */
        FAILED,
        /** The plugin says it is done — erased or pseudonymised, its call. */
        DONE
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plugin_id", nullable = false)
    private String pluginId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserDataErasure() {
        // for JPA
    }

    public UserDataErasure(UUID userId, String pluginId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.pluginId = pluginId;
        this.status = Status.PENDING.name();
        this.attempts = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Records a completed erasure — the plugin's own judgement about what "erased" meant. */
    public void succeeded() {
        this.status = Status.DONE.name();
        this.lastError = null;
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    /** Records a handler that threw. The debt stays open and is retried. */
    public void failed(String message) {
        this.status = Status.FAILED.name();
        // Bounded: a stack trace in a column nobody reads is not a diagnosis, and this is shown in admin.
        this.lastError = message == null ? null : message.substring(0, Math.min(message.length(), 500));
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    /** Records that the plugin could not be asked at all — installed, but switched off. */
    public void deferred(String reason) {
        this.status = Status.PENDING.name();
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPluginId() {
        return pluginId;
    }

    public Status getStatus() {
        return Status.valueOf(status);
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
