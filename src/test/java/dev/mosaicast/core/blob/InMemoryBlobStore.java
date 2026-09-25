// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import dev.mosaicast.core.web.NotFoundException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link NamedBlobStore} that keeps everything in a map — the second implementation, and the only real
 * test of whether the seam in §11 holds.
 *
 * <p>It exists because an abstraction with one implementation is an assertion, not an interface. Every gap
 * this file exposed was a real one: the plugin surface used to reach into {@code BlobRepository} for
 * listing, counting, quota and purge, so a backend that is not Postgres would have had no quota accounting
 * and an empty media library, and nothing would have said so.
 *
 * <p><strong>Deliberately self-contained</strong>, as a real object-store backend has to be: it holds its
 * own metadata rather than borrowing a Postgres index, because two stores of the same truth can disagree
 * and nothing can then say which is right.
 *
 * <p>It is a test double, not a preview of an implementation — a filesystem backend would keep sidecar
 * metadata and an object store would use object metadata plus a maintained size counter, since neither can
 * afford to walk its own namespace on every upload the way this one does.
 */
public class InMemoryBlobStore implements NamedBlobStore {

    /** The name a test's {@code mosaicast.blobs.namespaces} entry points at. */
    public static final String NAME = "memory";

    private static final BlobCapabilities CAPABILITIES = new BlobCapabilities(true, true);

    /** Insertion-ordered, so listing can be newest-first without a stored clock. */
    private final Map<UUID, Stored> byId = new LinkedHashMap<>();

    private record Stored(BlobMetadata meta, byte[] data) {
    }

    @Override
    public String backendName() {
        return NAME;
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return put(namespace, key, data, mime, null, null);
    }

    @Override
    public synchronized BlobRef put(String namespace, String key, InputStream data, String mime,
                                    String filename, UUID uploader) {
        byte[] bytes = readAll(data);
        // Replace-by-(namespace, key), the same contract Postgres has through its unique constraint.
        UUID id = find(namespace, key).map(existing -> existing.meta().ref().id()).orElseGet(UUID::randomUUID);
        BlobRef ref = new BlobRef(id, namespace);
        byId.remove(id);
        byId.put(id, new Stored(
                new BlobMetadata(ref, key, mime, bytes.length, Instant.now(), filename, uploader), bytes));
        return ref;
    }

    @Override
    public synchronized Optional<BlobMetadata> stat(String namespace, String key) {
        return find(namespace, key).map(Stored::meta);
    }

    @Override
    public synchronized Optional<BlobMetadata> stat(BlobRef ref) {
        return Optional.ofNullable(byId.get(ref.id())).map(Stored::meta);
    }

    @Override
    public synchronized BlobContent get(BlobRef ref) {
        Stored stored = require(ref);
        return new BlobContent(stored.meta().mime(), stored.meta().size(), stored.meta().updatedAt(),
                new ByteArrayInputStream(stored.data()));
    }

    @Override
    public synchronized BlobContent getRange(BlobRef ref, long start, long endInclusive) {
        Stored stored = require(ref);
        long total = stored.meta().size();
        long from = Math.max(0, start);
        long to = Math.min(endInclusive, total - 1);
        byte[] slice = to < from
                ? new byte[0]
                : java.util.Arrays.copyOfRange(stored.data(), (int) from, (int) to + 1);
        return new BlobContent(stored.meta().mime(), total, stored.meta().updatedAt(),
                new ByteArrayInputStream(slice));
    }

    @Override
    public synchronized void delete(BlobRef ref) {
        byId.remove(ref.id());
    }

    @Override
    public synchronized List<BlobMetadata> list(String namespace, int page, int size) {
        List<BlobMetadata> newestFirst = new ArrayList<>(inNamespace(namespace));
        java.util.Collections.reverse(newestFirst);
        int from = Math.min(page * size, newestFirst.size());
        return List.copyOf(newestFirst.subList(from, Math.min(from + size, newestFirst.size())));
    }

    @Override
    public synchronized long count(String namespace) {
        return inNamespace(namespace).size();
    }

    @Override
    public synchronized long usedBytes(String namespace) {
        return inNamespace(namespace).stream().mapToLong(BlobMetadata::size).sum();
    }

    @Override
    public synchronized int deleteNamespace(String namespace) {
        List<UUID> doomed = byId.values().stream()
                .filter(stored -> stored.meta().ref().namespace().equals(namespace))
                .map(stored -> stored.meta().ref().id())
                .toList();
        doomed.forEach(byId::remove);
        return doomed.size();
    }

    /**
     * A stand-in for a presigned URL, so the "backend serves its own bytes" branch is exercised somewhere.
     *
     * <p>Nothing consumes it yet — the plugin surface always proxies — which is itself worth knowing: the
     * capability is advertised and unused, and the first backend that can really hand out URLs will need a
     * caller that redirects rather than reads.
     */
    @Override
    public synchronized Optional<String> directUrl(BlobRef ref, AccessContext ctx) {
        return byId.containsKey(ref.id()) ? Optional.of("memory://" + ref.namespace() + "/" + ref.id())
                : Optional.empty();
    }

    @Override
    public BlobCapabilities capabilities() {
        return CAPABILITIES;
    }

    /** Keeps the id it is handed, as a migration target must (§11, #105). */
    @Override
    public synchronized BlobRef putVerbatim(BlobMetadata metadata, InputStream data) {
        byte[] bytes = readAll(data);
        UUID id = metadata.ref().id();
        byId.remove(id);
        byId.put(id, new Stored(new BlobMetadata(metadata.ref(), metadata.key(), metadata.mime(),
                bytes.length, metadata.updatedAt() == null ? java.time.Instant.now() : metadata.updatedAt(),
                metadata.filename()), bytes));
        return metadata.ref();
    }

    @Override
    public synchronized java.util.List<String> namespacesUnder(String prefix) {
        return byId.values().stream()
                .map(stored -> stored.meta().ref().namespace())
                .filter(namespace -> namespace.equals(prefix) || namespace.startsWith(prefix + "/"))
                .distinct()
                .sorted()
                .toList();
    }

    /** Everything stored so far, for a test asserting the bytes really did not go to Postgres. */
    public synchronized int size() {
        return byId.size();
    }

    private List<BlobMetadata> inNamespace(String namespace) {
        return byId.values().stream()
                .map(Stored::meta)
                .filter(meta -> meta.ref().namespace().equals(namespace))
                .sorted(Comparator.comparing(BlobMetadata::updatedAt))
                .toList();
    }

    private Optional<Stored> find(String namespace, String key) {
        return byId.values().stream()
                .filter(stored -> stored.meta().ref().namespace().equals(namespace)
                        && stored.meta().key().equals(key))
                .findFirst();
    }

    private Stored require(BlobRef ref) {
        Stored stored = byId.get(ref.id());
        if (stored == null) {
            throw new NotFoundException("Blob not found: " + ref.id());
        }
        return stored;
    }

    private static byte[] readAll(InputStream data) {
        try (data) {
            return data.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob data", e);
        }
    }
}
