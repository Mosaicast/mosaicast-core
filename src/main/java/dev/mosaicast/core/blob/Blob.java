// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored binary object (ARCHITECTURE §11). In v1 the bytes live in Postgres {@code BYTEA}; the same
 * {@link BlobStore} interface fronts S3/CDN backends later, routed per namespace. The {@link #data} is
 * lazily fetched so metadata lookups (ETag, existence) don't pull the whole blob into memory.
 */
@Entity
@Table(name = "blob")
public class Blob {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String namespace;

    @Column(name = "blob_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Blob() {
        // for JPA
    }

    Blob(UUID id, String namespace, String key, String mime, byte[] data) {
        this.id = id;
        this.namespace = namespace;
        this.key = key;
        this.mime = mime;
        this.data = data;
        this.sizeBytes = data.length;
    }

    void replace(String mime, byte[] data) {
        this.mime = mime;
        this.data = data;
        this.sizeBytes = data.length;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getKey() {
        return key;
    }

    public String getMime() {
        return mime;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public byte[] getData() {
        return data;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
