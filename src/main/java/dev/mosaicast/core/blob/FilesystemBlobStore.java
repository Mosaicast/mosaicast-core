// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import dev.mosaicast.core.web.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link BlobStore} backed by a directory on disk (ARCHITECTURE §11.3).
 *
 * <p>The second real backend, and the one most installs actually want: a self-hoster with a big disk and no
 * object store is the common case, and audio does not belong in Postgres. Registered as
 * {@code filesystem} and routed per namespace — {@code mosaicast.blobs.namespaces.plugin=filesystem} moves
 * every plugin's files here while branding stays where it is.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   &lt;root&gt;/&lt;namespace&gt;/objects/&lt;id&gt;        the bytes
 *   &lt;root&gt;/&lt;namespace&gt;/objects/&lt;id&gt;.json   mime, key, filename, uploader, updatedAt
 *   &lt;root&gt;/&lt;namespace&gt;/keys/&lt;key&gt;          the id that key currently resolves to
 * </pre>
 *
 * <p>Two lookups, so two indexes. {@link #stat(BlobRef)} has only an id and {@link #stat(String, String)}
 * has only a key; a single directory keyed by one of them would make the other a directory walk, on a path
 * that runs per request. The sidecar exists because a filesystem carries no metadata of its own — and it is
 * a plain file rather than an xattr, because xattrs do not survive a tarball, an rsync without {@code -X},
 * or a filesystem that does not implement them, which is exactly the backup an operator will rely on.
 *
 * <h2>Writes are atomic, or they did not happen</h2>
 *
 * <p>Bytes go to a temp file in the same directory and are moved into place with
 * {@link StandardCopyOption#ATOMIC_MOVE}; the sidecar follows the same way. A crash mid-upload leaves a
 * temp file, never a half-written object that {@code stat} would report a size for.
 *
 * <h2>Path safety is the whole risk</h2>
 *
 * <p>A namespace or key that escaped the root would turn a plugin upload into a write anywhere the process
 * can reach. Both are host-generated today — the namespace from a plugin id, the key a UUID the host mints
 * — and this class asserts that rather than trusting it: every segment is matched against a conservative
 * pattern, and the resolved path is checked to still be inside the root. The check is cheap and the failure
 * it prevents is not recoverable.
 */
@Component
@ConditionalOnProperty(prefix = "mosaicast.blobs.filesystem", name = "root")
public class FilesystemBlobStore implements NamedBlobStore {

    /** The name a {@code mosaicast.blobs.*} rule routes to. */
    public static final String NAME = "filesystem";

    private static final Logger log = LoggerFactory.getLogger(FilesystemBlobStore.class);

    /** Ranges are plain seeks here; presigned URLs are an object store's trick, not a disk's. */
    private static final BlobCapabilities CAPABILITIES = new BlobCapabilities(true, false);

    /**
     * What a namespace segment or a key may contain.
     *
     * <p>Deliberately narrower than what a filesystem accepts: no slash, no dot-segment, nothing that
     * needs escaping. Everything the host generates fits — plugin ids are already constrained, keys are
     * UUIDs, and branding uses {@code logo} / {@code favicon} — so a value that does not fit is a bug
     * rather than a user's unusual filename, and the filename it came from is kept in the sidecar anyway.
     */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private static final String OBJECTS = "objects";
    private static final String KEYS = "keys";
    private static final String SIDECAR_SUFFIX = ".json";

    private final Path root;
    private final ObjectMapper json = JsonMapper.builder().build();

    public FilesystemBlobStore(BlobStoreProperties properties) {
        this.root = Path.of(properties.filesystem().root()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // Failing startup is right: an install configured to store files somewhere it cannot write
            // would otherwise accept an upload and lose it.
            throw new IllegalStateException("Cannot create the blob root " + root, e);
        }
        log.info("Filesystem blob backend rooted at {}", root);
    }

    @Override
    public String backendName() {
        return NAME;
    }

    @Override
    public BlobCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return put(namespace, key, data, mime, null, null);
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime, String filename,
                       UUID uploader) {
        requireSafeNamespace(namespace);
        requireSafeSegment(key, "key");
        Path objects = directory(namespace, OBJECTS);
        Path keys = directory(namespace, KEYS);
        // The same key keeps its id, so a replace stays one object rather than accumulating orphans —
        // the semantics the Postgres backend already has.
        UUID id = readKey(keys.resolve(key)).orElseGet(UUID::randomUUID);
        try {
            Path object = objects.resolve(id.toString());
            long size = writeAtomically(object, data);
            writeAtomically(objects.resolve(id + SIDECAR_SUFFIX),
                    json.writeValueAsBytes(new Sidecar(key, mime, size, Instant.now().toString(), filename,
                            uploader == null ? null : uploader.toString())));
            writeAtomically(keys.resolve(key), id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new BlobRef(id, namespace);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store blob " + key + " in " + namespace, e);
        }
    }

    @Override
    public Optional<BlobMetadata> stat(String namespace, String key) {
        requireSafeNamespace(namespace);
        requireSafeSegment(key, "key");
        return readKey(directory(namespace, KEYS).resolve(key))
                .flatMap(id -> stat(new BlobRef(id, namespace)));
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        requireSafeNamespace(ref.namespace());
        return readSidecar(ref).map(sidecar -> sidecar.toMetadata(ref));
    }

    @Override
    public BlobContent get(BlobRef ref) {
        return open(ref, -1, -1);
    }

    @Override
    public BlobContent getRange(BlobRef ref, long start, long endInclusive) {
        return open(ref, start, endInclusive);
    }

    @Override
    public void delete(BlobRef ref) {
        requireSafeNamespace(ref.namespace());
        Optional<Sidecar> sidecar = readSidecar(ref);
        try {
            Files.deleteIfExists(objectPath(ref));
            Files.deleteIfExists(sidecarPath(ref));
            // Last, and only when it still points at this object: a key that was re-put in the meantime
            // belongs to the newer object, and removing it would strand that one.
            if (sidecar.isPresent()) {
                Path keyFile = directory(ref.namespace(), KEYS).resolve(sidecar.get().key());
                if (readKey(keyFile).filter(ref.id()::equals).isPresent()) {
                    Files.deleteIfExists(keyFile);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete blob " + ref.id(), e);
        }
    }

    @Override
    public List<BlobMetadata> list(String namespace, int page, int size) {
        requireSafeNamespace(namespace);
        int from = Math.max(0, page) * Math.max(1, size);
        return sidecars(namespace)
                .sorted(Comparator.comparing((BlobMetadata m) -> m.updatedAt()).reversed()
                        .thenComparing(m -> m.ref().id()))
                .skip(from)
                .limit(Math.max(1, size))
                .toList();
    }

    @Override
    public long count(String namespace) {
        requireSafeNamespace(namespace);
        return sidecars(namespace).count();
    }

    /**
     * The namespace's total size, by summing the sidecars.
     *
     * <p>Called on every upload, so it is a directory walk on the write path — acceptable for the install
     * this backend is for (one operator, thousands of files, not millions), and the obvious thing to
     * replace with a maintained counter if a large install proves otherwise. Reading the sidecars rather
     * than the objects keeps it to one stat-and-parse per file instead of two.
     */
    @Override
    public long usedBytes(String namespace) {
        requireSafeNamespace(namespace);
        return sidecars(namespace).mapToLong(BlobMetadata::size).sum();
    }

    @Override
    public int deleteNamespace(String namespace) {
        requireSafeNamespace(namespace);
        Path directory = root.resolve(namespace).normalize();
        requireInsideRoot(directory);
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int removed = (int) sidecars(namespace).count();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot purge namespace " + namespace, e);
        }
        return removed;
    }

    /**
     * No direct URL: a file on the app's own disk is not reachable without the app.
     *
     * <p>This is the honest answer rather than a missing feature — {@code directUrl} exists for a backend
     * that can hand out a presigned URL, and answering empty is what tells the caller to stream.
     */
    @Override
    public Optional<String> directUrl(BlobRef ref, AccessContext ctx) {
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private BlobContent open(BlobRef ref, long start, long endInclusive) {
        Sidecar sidecar = readSidecar(ref)
                .orElseThrow(() -> new NotFoundException("No such blob: " + ref.id()));
        Path object = objectPath(ref);
        try {
            long total = Files.size(object);
            InputStream stream = Files.newInputStream(object);
            if (start >= 0) {
                long from = Math.max(0, start);
                long to = Math.min(endInclusive, total - 1);
                long length = Math.max(0, to - from + 1);
                stream.skipNBytes(from);
                stream = new BoundedInputStream(stream, length);
            }
            return new BlobContent(sidecar.mime(), total, sidecar.updatedAtInstant(), stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read blob " + ref.id(), e);
        }
    }

    /** Every readable sidecar in a namespace, as metadata. A file being written right now is skipped. */
    private Stream<BlobMetadata> sidecars(String namespace) {
        Path objects = root.resolve(namespace).resolve(OBJECTS).normalize();
        requireInsideRoot(objects);
        if (!Files.isDirectory(objects)) {
            return Stream.empty();
        }
        List<BlobMetadata> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(objects)) {
            files.filter(path -> path.getFileName().toString().endsWith(SIDECAR_SUFFIX)).forEach(path -> {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - SIDECAR_SUFFIX.length());
                try {
                    Sidecar sidecar = json.readValue(Files.readAllBytes(path), Sidecar.class);
                    found.add(sidecar.toMetadata(new BlobRef(UUID.fromString(id), namespace)));
                } catch (RuntimeException | IOException e) {
                    // A sidecar that cannot be parsed is a file this store did not finish writing, or one
                    // something else put here. Skipping keeps a listing usable; failing would make one bad
                    // file hide a whole namespace.
                    log.warn("Skipping unreadable blob sidecar {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list namespace " + namespace, e);
        }
        return found.stream();
    }

    private Optional<Sidecar> readSidecar(BlobRef ref) {
        Path path = sidecarPath(ref);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readAllBytes(path), Sidecar.class));
        } catch (RuntimeException | IOException e) {
            log.warn("Unreadable blob sidecar {}: {}", path, e.toString());
            return Optional.empty();
        }
    }

    private Optional<UUID> readKey(Path keyFile) {
        if (!Files.isRegularFile(keyFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(Files.readString(keyFile).strip()));
        } catch (RuntimeException | IOException e) {
            log.warn("Unreadable blob key pointer {}: {}", keyFile, e.toString());
            return Optional.empty();
        }
    }

    private Path objectPath(BlobRef ref) {
        Path path = root.resolve(ref.namespace()).resolve(OBJECTS).resolve(ref.id().toString()).normalize();
        requireInsideRoot(path);
        return path;
    }

    private Path sidecarPath(BlobRef ref) {
        return objectPath(ref).resolveSibling(ref.id() + SIDECAR_SUFFIX);
    }

    private Path directory(String namespace, String kind) {
        Path path = root.resolve(namespace).resolve(kind).normalize();
        requireInsideRoot(path);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + path, e);
        }
        return path;
    }

    /** Writes bytes through a temp file in the same directory, so a reader never sees a partial object. */
    private static long writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".tmp-", null);
        Files.write(temp, bytes);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return bytes.length;
    }

    /** As above, streaming — the upload path, where the size is only known once it is written. */
    private static long writeAtomically(Path target, InputStream data) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".tmp-", null);
        long size = Files.copy(data, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return size;
    }

    private static void requireSafeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("a blob namespace cannot be blank");
        }
        for (String segment : namespace.split("/")) {
            requireSafeSegment(segment, "namespace segment");
        }
    }

    private static void requireSafeSegment(String segment, String what) {
        if (segment == null || !SAFE_SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                    "unsafe blob %s: %s".formatted(what, segment));
        }
    }

    /** Belt to the pattern's braces: whatever the segments were, the resolved path must be under the root. */
    private void requireInsideRoot(Path path) {
        if (!path.normalize().startsWith(root)) {
            throw new IllegalArgumentException("blob path escapes the root: " + path);
        }
    }

    /**
     * The sidecar's shape. Timestamps are strings so the file stays readable and portable — an operator
     * looking at this directory during an incident should not need the app to interpret it.
     *
     * <p>Unknown fields are ignored, because this file has a second writer: {@code scripts/migrate-blobs.py}
     * produces these when moving a namespace between backends. A field added on either side must not make
     * the other refuse to read an object, which is the difference between a forward-compatible format and
     * a version lock between a script and the app that happens to be deployed.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Sidecar(String key, String mime, long size, String updatedAt, String filename, String uploader) {

        Instant updatedAtInstant() {
            try {
                return Instant.parse(updatedAt);
            } catch (RuntimeException e) {
                return Instant.EPOCH;
            }
        }

        BlobMetadata toMetadata(BlobRef ref) {
            return new BlobMetadata(ref, key, mime, size, updatedAtInstant(), filename);
        }
    }

    /** Caps a stream at {@code remaining} bytes — the range read, without buffering the slice. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
