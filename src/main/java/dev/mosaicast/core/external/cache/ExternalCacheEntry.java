// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/** One cached external-service result (ARCHITECTURE §12.7). */
@Entity
@Table(name = "external_cache")
public class ExternalCacheEntry {

    @Id
    @Column(name = "cache_key", nullable = false)
    private String cacheKey;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Column(name = "payload_bytes", nullable = false)
    private int payloadBytes;

    @Column(name = "hits", nullable = false)
    private long hits;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_read_at", nullable = false)
    private Instant lastReadAt = Instant.now();

    /** {@code null} means it never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected ExternalCacheEntry() {
        // for JPA
    }

    public ExternalCacheEntry(String cacheKey, String kind, String providerId, JsonNode payload,
                              int payloadBytes, Instant expiresAt) {
        this.cacheKey = cacheKey;
        this.kind = kind;
        this.providerId = providerId;
        this.payload = payload;
        this.payloadBytes = payloadBytes;
        this.expiresAt = expiresAt;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getHits() {
        return hits;
    }
}
