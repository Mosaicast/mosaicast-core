// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An admin-set override for one config field a plugin declares in its manifest (ARCHITECTURE §7.2). Absent
 * means "the manifest default still applies", so clearing an override deletes the row rather than storing a
 * sentinel — see {@link PluginConfigImpl} for the resolution order.
 */
@Entity
@Table(name = "plugin_config")
public class PluginConfigValue {

    @EmbeddedId
    private PluginConfigValueKey id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PluginConfigValue() {
        // for JPA
    }

    public PluginConfigValue(PluginConfigValueKey id, JsonNode value) {
        this.id = id;
        this.value = value;
    }

    /** Replaces the stored override. */
    public void overwrite(JsonNode value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public PluginConfigValueKey getId() {
        return id;
    }

    public JsonNode getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
