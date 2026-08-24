// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import dev.mosaicast.core.web.NotFoundException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The v1 {@link BlobStore} backend: bytes in Postgres {@code BYTEA} (ARCHITECTURE §11). Ranges are read
 * with a server-side {@code substring}, so a range request never materializes the whole blob. Supports
 * ranges; no presigned URLs (that arrives with S3).
 */
@Component
public class PostgresBlobStore implements NamedBlobStore {

    /** The name {@code mosaicast.blobs.*} routes to, and the default backend. */
    public static final String NAME = "postgres";

    private static final BlobCapabilities CAPABILITIES = new BlobCapabilities(true, false);

    private final BlobRepository blobs;

    @Override
    public String backendName() {
        return NAME;
    }

    public PostgresBlobStore(BlobRepository blobs) {
        this.blobs = blobs;
    }

    @Override
    @Transactional
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return put(namespace, key, data, mime, null, null);
    }

    @Override
    @Transactional
    public BlobRef put(String namespace, String key, InputStream data, String mime, String filename,
                       UUID uploader) {
        byte[] bytes = readAll(data);
        Blob blob = blobs.findByNamespaceAndKey(namespace, key)
                .map(existing -> {
                    existing.replace(mime, bytes);
                    return existing;
                })
                .orElseGet(() -> new Blob(UUID.randomUUID(), namespace, key, mime, bytes));
        blob.attribute(filename, uploader);
        blobs.save(blob);
        return new BlobRef(blob.getId(), blob.getNamespace());
    }

    /**
     * Writes an object keeping its id — the migration path (§11, #105).
     *
     * <p>Matched on the id rather than on {@code (namespace, key)}: the id is what a plugin holds, and an
     * object arriving from another backend has to land on it even if some other row happens to occupy that
     * key. Idempotent, so a re-run of an interrupted migration overwrites rather than duplicates.
     */
    @Override
    @Transactional
    public BlobRef putVerbatim(BlobMetadata metadata, InputStream data) {
        byte[] bytes = readAll(data);
        Blob blob = blobs.findById(metadata.ref().id())
                .map(existing -> {
                    existing.replace(metadata.mime(), bytes);
                    return existing;
                })
                .orElseGet(() -> new Blob(metadata.ref().id(), metadata.ref().namespace(), metadata.key(),
                        metadata.mime(), bytes));
        blob.attribute(metadata.filename(), null);
        blobs.save(blob);
        return new BlobRef(blob.getId(), blob.getNamespace());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> namespacesUnder(String prefix) {
        return blobs.namespacesUnder(prefix, prefix + "/%");
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

    /**
     * Always empty: there is no URL that reaches into a {@code BYTEA} column, so callers serve the bytes.
     *
     * <p>This used to answer {@code "/api/blobs/<id>"} for a route nothing has ever mapped — a contract that
     * read as satisfied and would have 404ed the first caller to believe it.
     */
    @Override
    public Optional<String> directUrl(BlobRef ref, AccessContext ctx) {
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BlobMetadata> list(String namespace, int page, int size) {
        return blobs.listByNamespace(namespace, PageRequest.of(Math.max(0, page), Math.max(1, size)));
    }

    @Override
    @Transactional(readOnly = true)
    public long count(String namespace) {
        return blobs.countByNamespace(namespace);
    }

    /**
     * Sums an indexed column — cheap enough to run on every upload, which is what the quota check does.
     *
     * <p>Worth stating because it is the operation a future object-store backend cannot implement this way:
     * there, this becomes a maintained counter. Postgres being good at it is the reason the interface can
     * afford to ask.
     */
    @Override
    @Transactional(readOnly = true)
    public long usedBytes(String namespace) {
        return blobs.sumSizeBytesByNamespace(namespace);
    }

    @Override
    @Transactional
    public int deleteNamespace(String namespace) {
        return blobs.deleteByNamespace(namespace);
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
                blob.getKey(), blob.getMime(), blob.getSizeBytes(), blob.getUpdatedAt(), blob.getFilename());
    }

    private static byte[] readAll(InputStream data) {
        try (data) {
            return data.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob data", e);
        }
    }
}
