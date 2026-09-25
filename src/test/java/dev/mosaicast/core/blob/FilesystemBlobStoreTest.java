// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The filesystem backend (ARCHITECTURE §11), against a real directory.
 *
 * <p>Two things are worth testing here beyond "the bytes come back": that a namespace or key cannot address
 * anything outside the root — the one failure in this class that is not recoverable — and that the
 * namespace-scoped answers (count, usedBytes, purge) are right, since those are what a plugin's quota is
 * enforced from.
 */
class FilesystemBlobStoreTest {

    @TempDir
    Path root;

    private FilesystemBlobStore store;

    @BeforeEach
    void store() {
        store = new FilesystemBlobStore(new BlobStoreProperties(
                "postgres", Map.of(), new BlobStoreProperties.Filesystem(root.toString())));
    }

    @Test
    void storesAndReadsBackTheBytesWithTheirMetadata() {
        BlobRef ref = store.put("plugin/wiki", "k1", bytes("a squid"), "image/png", "squid.png", null);

        assertThat(store.stat(ref)).hasValueSatisfying(meta -> {
            assertThat(meta.mime()).isEqualTo("image/png");
            assertThat(meta.size()).isEqualTo(7);
            assertThat(meta.filename()).isEqualTo("squid.png");
            assertThat(meta.key()).isEqualTo("k1");
        });
        assertThat(read(store.get(ref))).isEqualTo("a squid");

        // Both lookups resolve: stat(ref) has only an id, stat(namespace, key) has only a key, and the
        // layout keeps each of them one file read rather than a directory walk.
        assertThat(store.stat("plugin/wiki", "k1")).hasValueSatisfying(
                meta -> assertThat(meta.ref().id()).isEqualTo(ref.id()));
    }

    @Test
    void aRangeIsASeekRatherThanAWholeRead() {
        BlobRef ref = store.put("branding", "logo", bytes("0123456789"), "image/png");

        assertThat(read(store.getRange(ref, 2, 5))).isEqualTo("2345");
        // The total is the object's, not the slice's — a Content-Range needs both.
        assertThat(store.getRange(ref, 2, 5).totalSize()).isEqualTo(10);
    }

    @Test
    void re_puttingAKeyReplacesTheObjectRatherThanAccumulatingOrphans() {
        BlobRef first = store.put("plugin/wiki", "k1", bytes("old"), "text/plain");
        BlobRef second = store.put("plugin/wiki", "k1", bytes("new bytes"), "text/plain");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(read(store.get(second))).isEqualTo("new bytes");
        assertThat(store.count("plugin/wiki")).isEqualTo(1);
        assertThat(store.usedBytes("plugin/wiki")).isEqualTo(9);
    }

    @Test
    void countsAndTotalsPerNamespaceBecauseAQuotaIsEnforcedFromThem() {
        store.put("plugin/wiki", "a", bytes("12345"), "text/plain");
        store.put("plugin/wiki", "b", bytes("123"), "text/plain");
        store.put("plugin/bingo", "a", bytes("1"), "text/plain");

        assertThat(store.usedBytes("plugin/wiki")).isEqualTo(8);
        assertThat(store.count("plugin/wiki")).isEqualTo(2);
        // Namespaces do not leak into each other, which is what makes them the unit of ownership.
        assertThat(store.usedBytes("plugin/bingo")).isEqualTo(1);
        assertThat(store.usedBytes("plugin/nobody")).isZero();
    }

    @Test
    void deletingRemovesTheObjectItsSidecarAndItsKey() {
        BlobRef ref = store.put("plugin/wiki", "k1", bytes("gone soon"), "text/plain");

        store.delete(ref);

        assertThat(store.stat(ref)).isEmpty();
        assertThat(store.stat("plugin/wiki", "k1")).isEmpty();
        assertThat(store.count("plugin/wiki")).isZero();
        assertThat(store.usedBytes("plugin/wiki")).isZero();
    }

    @Test
    void purgingANamespaceTakesEverythingInItAndNothingElse() {
        store.put("plugin/wiki", "a", bytes("12"), "text/plain");
        store.put("plugin/wiki", "b", bytes("34"), "text/plain");
        store.put("plugin/bingo", "a", bytes("5"), "text/plain");

        assertThat(store.deleteNamespace("plugin/wiki")).isEqualTo(2);

        assertThat(store.count("plugin/wiki")).isZero();
        assertThat(store.count("plugin/bingo")).isEqualTo(1);
    }

    @Test
    void aNamespaceOrKeyCannotAddressAnythingOutsideTheRoot() throws IOException {
        Path outside = root.getParent().resolve("outside.txt");
        Files.writeString(outside, "not yours");

        // Both are host-generated today — a plugin id and a UUID — and this is the assertion that keeps
        // that a property rather than a habit.
        assertThatThrownBy(() -> store.put("../..", "k", bytes("x"), "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("plugin/wiki", "../../outside.txt", bytes("x"), "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.stat("plugin/../..", "k"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Files.readString(outside)).isEqualTo("not yours");
    }

    @Test
    void aKeyReadBackFromASidecarCannotAddressAnythingOutsideTheStore() throws IOException {
        // `put` refuses such a key, but `delete` reads the key back from the sidecar on disk, and that file
        // has other writers — the migration script, a restored backup. The victim holds the object's id,
        // which is exactly what the "does the key still point here?" check looks for before deleting.
        BlobRef ref = store.put("plugin/wiki", "k1", bytes("x"), "text/plain");
        Path victim = root.getParent().resolve("victim-" + ref.id());
        Files.writeString(victim, ref.id().toString());
        Path sidecar = root.resolve("plugin/wiki/objects").resolve(ref.id() + ".json");
        Files.writeString(sidecar, Files.readString(sidecar)
                .replace("\"k1\"", "\"../../../../victim-" + ref.id() + "\""));
        try {
            store.delete(ref);

            assertThat(victim).exists();
            // The delete itself still happened: an unsafe key costs its key file, not the deletion.
            assertThat(store.stat(ref)).isEmpty();
        } finally {
            Files.deleteIfExists(victim);
        }
    }

    @Test
    void anUnreadableSidecarCostsItsOwnFileAndNotTheListing() throws IOException {
        store.put("plugin/wiki", "good", bytes("fine"), "text/plain");
        Files.writeString(root.resolve("plugin/wiki/objects").resolve(UUID.randomUUID() + ".json"),
                "{ not json");

        // One corrupt file must not hide a namespace: a listing that fails entirely is worse than one
        // missing an entry nothing can read anyway.
        assertThat(store.list("plugin/wiki", 0, 10)).singleElement()
                .satisfies(meta -> assertThat(meta.key()).isEqualTo("good"));
    }

    @Test
    void readsAnObjectWrittenByTheMigrationScript() throws IOException {
        // The layout has a second writer — scripts/migrate-blobs.py, which moves a namespace between
        // backends without linking against this class. This is that contract, pinned: the exact files the
        // script produces, including a null filename and a field this version does not know about.
        UUID id = UUID.randomUUID();
        Path objects = root.resolve("plugin/wiki/objects");
        Path keys = root.resolve("plugin/wiki/keys");
        Files.createDirectories(objects);
        Files.createDirectories(keys);
        Files.write(objects.resolve(id.toString()), "migrated".getBytes(StandardCharsets.UTF_8));
        Files.writeString(objects.resolve(id + ".json"), """
                {"key": "k1", "mime": "image/png", "size": 8, "updatedAt": "2026-08-24T13:09:36.138Z",
                 "filename": null, "uploader": null, "somethingNewer": 1}
                """);
        Files.writeString(keys.resolve("k1"), id.toString());

        BlobRef ref = new BlobRef(id, "plugin/wiki");
        assertThat(store.stat(ref)).hasValueSatisfying(meta -> {
            assertThat(meta.mime()).isEqualTo("image/png");
            assertThat(meta.size()).isEqualTo(8);
            assertThat(meta.updatedAt()).isEqualTo(Instant.parse("2026-08-24T13:09:36.138Z"));
        });
        assertThat(read(store.get(ref))).isEqualTo("migrated");
        // And by key, which is the pointer file the script writes separately.
        assertThat(store.stat("plugin/wiki", "k1")).hasValueSatisfying(
                meta -> assertThat(meta.ref().id()).isEqualTo(id));
        assertThat(store.usedBytes("plugin/wiki")).isEqualTo(8);
    }

    @Test
    void offersNoDirectUrlBecauseADiskHasNone() {
        BlobRef ref = store.put("branding", "logo", bytes("x"), "image/png");

        assertThat(store.directUrl(ref, AccessContext.anonymous())).isEmpty();
        assertThat(store.capabilities().supportsRange()).isTrue();
        assertThat(store.capabilities().supportsPresignedUrls()).isFalse();
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(BlobContent content) {
        try (InputStream stream = content.stream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
