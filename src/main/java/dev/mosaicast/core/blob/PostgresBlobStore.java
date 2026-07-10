// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import dev.mosaicast.core.web.NotFoundException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The v1 {@link BlobStore} backend: bytes in Postgres {@code BYTEA} (ARCHITECTURE §11). Ranges are read
 * with a server-side {@code substring}, so a range request never materializes the whole blob. Supports
 * ranges; no presigned URLs (that arrives with S3).
 */
@Component
public class PostgresBlobStore implements BlobStore {

    private static final BlobCapabilities CAPABILITIES = new BlobCapabilities(true, false);

    private final BlobRepository blobs;

    public PostgresBlobStore(BlobRepository blobs) {
        this.blobs = blobs;
    }

    @Override
    @Transactional
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        byte[] bytes = readAll(data);
        Blob blob = blobs.findByNamespaceAndKey(namespace, key)
                .map(existing -> {
                    existing.replace(mime, bytes);
                    return existing;
                })
                .orElseGet(() -> new Blob(UUID.randomUUID(), namespace, key, mime, bytes));
        blobs.save(blob);
        return new BlobRef(blob.getId(), blob.getNamespace());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlobMetadata> stat(String namespace, String key) {
        return blobs.findByNamespaceAndKey(namespace, key).map(PostgresBlobStore::toMetadata);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlobMetadata> stat(BlobRef ref) {
        return blobs.findById(ref.id()).map(PostgresBlobStore::toMetadata);
    }

    @Override
    @Transactional(readOnly = true)
    public BlobContent get(BlobRef ref) {
        Blob blob = require(ref);
        return new BlobContent(blob.getMime(), blob.getSizeBytes(), blob.getUpdatedAt(),
                new ByteArrayInputStream(blob.getData()));
    }

    @Override
    @Transactional(readOnly = true)
    public BlobContent getRange(BlobRef ref, long start, long endInclusive) {
        Blob blob = require(ref);
        long total = blob.getSizeBytes();
        long clampedStart = Math.max(0, start);
        long clampedEnd = Math.min(endInclusive, total - 1);
        long length = Math.max(0, clampedEnd - clampedStart + 1);
        byte[] slice = length == 0
                ? new byte[0]
                : blobs.readRange(ref.id(), Math.toIntExact(clampedStart + 1), Math.toIntExact(length));
        return new BlobContent(blob.getMime(), total, blob.getUpdatedAt(), new ByteArrayInputStream(slice));
    }

    @Override
    @Transactional
    public void delete(BlobRef ref) {
        blobs.deleteById(ref.id());
    }

    @Override
    public String urlFor(BlobRef ref, AccessContext ctx) {
        // Own-endpoint form; branding is served by its dedicated ETag endpoints (§12.2).
        return "/api/blobs/" + ref.id();
    }

    @Override
    public BlobCapabilities capabilities() {
        return CAPABILITIES;
    }

    private Blob require(BlobRef ref) {
        return blobs.findById(ref.id())
                .orElseThrow(() -> new NotFoundException("Blob not found: " + ref.id()));
    }

    private static BlobMetadata toMetadata(Blob blob) {
        return new BlobMetadata(new BlobRef(blob.getId(), blob.getNamespace()),
                blob.getKey(), blob.getMime(), blob.getSizeBytes(), blob.getUpdatedAt());
    }

    private static byte[] readAll(InputStream data) {
        try (data) {
            return data.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob data", e);
        }
    }
}
