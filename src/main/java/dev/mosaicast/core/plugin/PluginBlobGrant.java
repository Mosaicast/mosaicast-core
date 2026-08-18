// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An admin's explicit storage decision for one plugin (ARCHITECTURE §11.1).
 *
 * <p>Each column is independently optional: {@code null} means "no decision about this limit", and that one
 * falls back to the manifest's ask and then to the operator's default. So an admin can raise a wiki's total
 * without touching how large a single upload may be, which is the common case — a media library grows by
 * accumulating ordinary files, not by one enormous one.
 */
@Entity
@Table(name = "plugin_blob_grant")
public class PluginBlobGrant {

    @Id
    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(name = "quota_bytes")
    private Long quotaBytes;

    @Column(name = "max_file_bytes")
    private Long maxFileBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PluginBlobGrant() {
        // for JPA
    }

    PluginBlobGrant(String pluginId, Long quotaBytes, Long maxFileBytes) {
        this.pluginId = pluginId;
        set(quotaBytes, maxFileBytes);
    }

    /** Replaces both limits; {@code null} clears that one back to the manifest/operator fallback. */
    void set(Long quotaBytes, Long maxFileBytes) {
        this.quotaBytes = quotaBytes;
        this.maxFileBytes = maxFileBytes;
        this.updatedAt = Instant.now();
    }

    /** Whether this row still decides anything, or is an empty shell to be deleted. */
    boolean isEmpty() {
        return quotaBytes == null && maxFileBytes == null;
    }

    public String getPluginId() {
        return pluginId;
    }

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public Long getMaxFileBytes() {
        return maxFileBytes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
