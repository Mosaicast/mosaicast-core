// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import dev.mosaicast.core.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The v1 {@link BlobStore} backend: bytes in Postgres {@code BYTEA} (ARCHITECTURE §11).
 *
 * <p><strong>No call holds a whole blob in heap.</strong> It used to: {@code Blob.data} is declared lazy, but a
 * lazy {@code byte[]} needs bytecode enhancement this build does not have, so every {@code findById} — a
 * download, a range request, even an ETag check — loaded every byte, and a handful of concurrent downloads of
 * a data-export archive could take the heap with them. Now:
 * <ul>
 *   <li><strong>Metadata</strong> is read by column projection, never as an entity.</li>
 *   <li><strong>Reads stream</strong> in {@link #CHUNK_BYTES} pieces, each a server-side {@code substring},
 *       fetched as the caller consumes them. The column is stored {@code EXTERNAL} (uncompressed, out of
 *       line, V42), which is what lets Postgres answer a {@code substring} from the TOAST chunks it covers
 *       instead of decompressing the whole value first.</li>
 *   <li><strong>Writes stream</strong> too: the input is spooled to a temporary file to learn its length, then
 *       sent with {@code setBinaryStream}.</li>
 * </ul>
 *
 * <p>No presigned URLs; those arrive with an object-store backend.
 */
@Component
public class PostgresBlobStore implements NamedBlobStore {

    /** The name {@code mosaicast.blobs.*} routes to, and the default backend. */
    public static final String NAME = "postgres";

    /** How much of a blob one read fetches: bounds the heap a download costs, whatever the blob's size. */
    static final int CHUNK_BYTES = 1024 * 1024;

    private static final BlobCapabilities CAPABILITIES = new BlobCapabilities(true, false);

    private final BlobRepository blobs;
    private final JdbcTemplate jdbc;

    @Override
    public String backendName() {
        return NAME;
    }

    public PostgresBlobStore(BlobRepository blobs, JdbcTemplate jdbc) {
        this.blobs = blobs;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return put(namespace, key, data, mime, null, null);
    }

    /**
     * Stores a blob under {@code (namespace, key)}, replacing one already there — which keeps its id and its
     * creation time, as it always has.
     */
    @Override
    @Transactional
    public BlobRef put(String namespace, String key, InputStream data, String mime, String filename,
                       UUID uploader) {
        return withSpooled(data, (file, length) -> {
            try (InputStream in = Files.newInputStream(file)) {
                UUID id = jdbc.query(con -> {
                    var statement = con.prepareStatement("""
                            INSERT INTO blob (id, namespace, blob_key, mime, size_bytes, data, filename, created_by,
                                              created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                            ON CONFLICT (namespace, blob_key) DO UPDATE SET
                                mime = excluded.mime, size_bytes = excluded.size_bytes, data = excluded.data,
                                filename = excluded.filename, created_by = excluded.created_by, updated_at = now()
                            RETURNING id
                            """);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, namespace);
                    statement.setString(3, key);
                    statement.setString(4, mime);
                    statement.setLong(5, length);
                    statement.setBinaryStream(6, in, length);
                    statement.setString(7, filename);
                    statement.setObject(8, uploader);
                    return statement;
                }, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
                return new BlobRef(id, namespace);
            }
        });
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
        return withSpooled(data, (file, length) -> {
            try (InputStream in = Files.newInputStream(file)) {
                jdbc.update(con -> {
                    var statement = con.prepareStatement("""
                            INSERT INTO blob (id, namespace, blob_key, mime, size_bytes, data, filename, created_by,
                                              created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, now(), now())
                            ON CONFLICT (id) DO UPDATE SET
                                mime = excluded.mime, size_bytes = excluded.size_bytes, data = excluded.data,
                                filename = excluded.filename, created_by = NULL, updated_at = now()
                            """);
                    statement.setObject(1, metadata.ref().id());
                    statement.setString(2, metadata.ref().namespace());
                    statement.setString(3, metadata.key());
                    statement.setString(4, metadata.mime());
                    statement.setLong(5, length);
                    statement.setBinaryStream(6, in, length);
                    statement.setString(7, metadata.filename());
                    return statement;
                });
                return new BlobRef(metadata.ref().id(), metadata.ref().namespace());
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> namespacesUnder(String prefix) {
        return blobs.namespacesUnder(prefix, prefix + "/%");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlobMetadata> stat(String namespace, String key) {
        return blobs.findMetadataByNamespaceAndKey(namespace, key);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlobMetadata> stat(BlobRef ref) {
        return blobs.findMetadataById(ref.id());
    }

    @Override
    @Transactional(readOnly = true)
    public BlobContent get(BlobRef ref) {
        BlobMetadata blob = require(ref);
        return new BlobContent(blob.mime(), blob.size(), blob.updatedAt(),
                new ChunkedStream(ref.id(), blob, 0, blob.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public BlobContent getRange(BlobRef ref, long start, long endInclusive) {
        BlobMetadata blob = require(ref);
        long total = blob.size();
        long clampedStart = Math.max(0, start);
        long clampedEnd = Math.min(endInclusive, total - 1);
        long length = Math.max(0, clampedEnd - clampedStart + 1);
        return new BlobContent(blob.mime(), total, blob.updatedAt(),
                new ChunkedStream(ref.id(), blob, clampedStart, length));
    }

    /** Deleted by statement: {@code deleteById} would load the entity, bytes and all, just to remove it. */
    @Override
    @Transactional
    public void delete(BlobRef ref) {
        jdbc.update("DELETE FROM blob WHERE id = ?", ref.id());
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

    private BlobMetadata require(BlobRef ref) {
        return blobs.findMetadataById(ref.id())
                .orElseThrow(() -> new NotFoundException("Blob not found: " + ref.id()));
    }

    /** What to do with an input once it is on disk with a known length. */
    @FunctionalInterface
    private interface SpooledWrite {
        BlobRef write(Path file, long length) throws IOException;
    }

    /**
     * Copies the input to a temporary file, runs the write against it, and deletes the file.
     *
     * <p>A file rather than a buffer because the point is not to hold the blob in heap; the length is what
     * {@code setBinaryStream} needs to stream rather than buffer.
     */
    private static BlobRef withSpooled(InputStream data, SpooledWrite write) {
        Path file = null;
        try (data) {
            file = Files.createTempFile("mosaicast-blob-", ".bin");
            long length = Files.copy(data, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return write.write(file, length);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store blob data", e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A leftover temp file is the OS's to clean; the write itself has already succeeded or not.
                }
            }
        }
    }

    /**
     * A blob read lazily, one {@link #CHUNK_BYTES} {@code substring} at a time, as the response is written.
     *
     * <p>Each chunk is pinned to the {@code updated_at} the read started from. A blob replaced halfway through
     * a download would otherwise splice two versions into one response; instead the read fails, which the
     * client sees as a broken transfer it can retry.
     */
    private final class ChunkedStream extends InputStream {

        private final UUID id;
        private final Timestamp version;
        private long position;
        private final long end;
        private byte[] buffer = new byte[0];
        private int offset;

        ChunkedStream(UUID id, BlobMetadata blob, long start, long length) {
            this.id = id;
            this.version = Timestamp.from(blob.updatedAt());
            this.position = start;
            this.end = start + length;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return buffer[offset++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(length, buffer.length - offset);
            System.arraycopy(buffer, offset, target, targetOffset, n);
            offset += n;
            return n;
        }

        /** Makes sure there is something to read; false at the end of the range. */
        private boolean fill() throws IOException {
            if (offset < buffer.length) {
                return true;
            }
            if (position >= end) {
                return false;
            }
            int want = (int) Math.min(CHUNK_BYTES, end - position);
            // substring is 1-based.
            List<byte[]> chunk = jdbc.query(
                    "SELECT substring(data FROM ? FOR ?) FROM blob WHERE id = ? AND updated_at = ?",
                    (rs, i) -> rs.getBytes(1), Math.toIntExact(position + 1), want, id, version);
            if (chunk.isEmpty() || chunk.get(0) == null || chunk.get(0).length == 0) {
                throw new IOException("Blob " + id + " was replaced or deleted while it was being read");
            }
            buffer = chunk.get(0);
            offset = 0;
            position += buffer.length;
            return true;
        }
    }
}
