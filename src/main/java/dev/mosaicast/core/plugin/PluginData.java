// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One document in the generic per-plugin doc store (ARCHITECTURE §7.6), backing {@link DocStoreImpl}.
 * The JSON body is stored verbatim as {@code jsonb}; writes are last-write-wins.
 */
@Entity
@Table(name = "plugin_data")
public class PluginData {

    @EmbeddedId
    private PluginDataKey id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PluginData() {
        // for JPA
    }

    public PluginData(PluginDataKey id, JsonNode value) {
        this.id = id;
        this.value = value;
    }

    /** Replaces the JSON body on a fresh write (last-write-wins). */
    public void overwrite(JsonNode value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public PluginDataKey getId() {
        return id;
    }

    public JsonNode getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
