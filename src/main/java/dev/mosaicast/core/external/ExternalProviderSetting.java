// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * One admin-set value for a field a provider declares (ARCHITECTURE §12.7).
 *
 * <p>Absent means the descriptor's default still applies, so clearing an override deletes the row rather than
 * storing a sentinel — the same convention {@code plugin_config} uses, and the reason a JSON null is accepted
 * on the write path.
 *
 * <p>A {@code SECRET} value is stored here sealed by {@code SecretBox}; an {@code ENV_SECRET} never reaches
 * this table at all.
 */
@Entity
@Table(name = "external_provider_setting")
public class ExternalProviderSetting {

    @EmbeddedId
    private ExternalProviderSettingKey id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private JsonNode value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ExternalProviderSetting() {
        // for JPA
    }

    public ExternalProviderSetting(ExternalProviderSettingKey id, JsonNode value) {
        this.id = id;
        this.value = value;
    }

    public ExternalProviderSettingKey getId() {
        return id;
    }

    public JsonNode getValue() {
        return value;
    }

    public void setValue(JsonNode value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
