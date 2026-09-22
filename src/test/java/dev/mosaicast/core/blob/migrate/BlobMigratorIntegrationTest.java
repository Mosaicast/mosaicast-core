// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.blob.BlobRef;
import dev.mosaicast.core.blob.BlobStoreProperties;
import dev.mosaicast.core.blob.FilesystemBlobStore;
import dev.mosaicast.core.blob.PostgresBlobStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Moving blobs between the two real backends (ARCHITECTURE §11, issue #105).
 *
 * <p>This is the test an external migration script could not have: it runs the migrator against the same
 * {@code PostgresBlobStore} and {@code FilesystemBlobStore} the app uses, so a change to either backend's
 * layout is caught here rather than by an operator halfway through moving their files.
 *
 * <p>What is asserted is what makes a migration safe rather than merely functional — ids survive, bytes are
 * verified before anything is deleted, a second run is a no-op, and the namespace that must never move is
 * refused.
 */
@SpringBootTest(classes = dev.mosaicast.tools.blob.BlobMigratorApplication.class)
@ActiveProfiles("dev")
@Testcontainers
class BlobMigratorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    static Path root;

    @Autowired
    private PostgresBlobStore postgres;

    private FilesystemBlobStore filesystem() {
        return new FilesystemBlobStore(new BlobStoreProperties(
                "postgres", Map.of(), new BlobStoreProperties.Filesystem(root.toString())));
    }

    @Test
    void movesANamespaceKeepingIdsAndBytes() {
        BlobRef one = postgres.put("plugin/mover", "k1", bytes("a squid"), "image/png", "squid.png", null);
        BlobRef two = postgres.put("plugin/mover", "k2", bytes("a whale"), "application/pdf", null, null);
        FilesystemBlobStore target = filesystem();

        // Its own namespace, not the shared `plugin` prefix: every other method in this class writes under
        // `plugin/...` into the same store, so migrating the parent counts their blobs too and `copied()`
        // becomes whatever ran first. That prefix is covered by aPrefixCoversEveryNamespaceUnderIt, which
        // asserts membership rather than a total and is order-independent for that reason.
        BlobMigrator.Result result =
                new BlobMigrator(postgres, target, message -> { }).run("plugin/mover", false, false);

        assertThat(result.copied()).isEqualTo(2);
        // The id is the identity a plugin stored, so it has to be the same object on the other side —
        // a copy that renumbered would orphan every ref a plugin ever saved.
        assertThat(target.stat(one)).hasValueSatisfying(meta -> {
            assertThat(meta.key()).isEqualTo("k1");
            assertThat(meta.mime()).isEqualTo("image/png");
            assertThat(meta.filename()).isEqualTo("squid.png");
        });
        assertThat(read(target, two)).isEqualTo("a whale");
        // Copy, not move: the source is untouched until an explicit --delete-source.
        assertThat(postgres.stat(one)).isPresent();
    }

    @Test
    void aSecondRunCopiesNothing() {
        postgres.put("plugin/again", "k1", bytes("same bytes"), "text/plain", null, null);
        FilesystemBlobStore target = filesystem();
        BlobMigrator migrator = new BlobMigrator(postgres, target, message -> { });

        migrator.run("plugin/again", false, false);
        BlobMigrator.Result second = migrator.run("plugin/again", false, false);

        // What makes an interrupted run resumable rather than something to unpick by hand.
        assertThat(second.copied()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
    }

    @Test
    void deleteSourceOnlyAfterEverythingVerified() {
        BlobRef ref = postgres.put("plugin/leaving", "k1", bytes("bye"), "text/plain", null, null);
        FilesystemBlobStore target = filesystem();

        BlobMigrator.Result result =
                new BlobMigrator(postgres, target, message -> { }).run("plugin/leaving", true, false);

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(postgres.stat(ref)).isEmpty();
        assertThat(read(target, ref)).isEqualTo("bye");
    }

    @Test
    void goesBackTheOtherWay() {
        FilesystemBlobStore source = filesystem();
        BlobRef ref = source.put("plugin/back", "k1", bytes("returning"), "text/plain", "note.txt", null);

        new BlobMigrator(source, postgres, message -> { }).run("plugin/back", false, false);

        assertThat(postgres.stat(ref)).hasValueSatisfying(meta -> {
            assertThat(meta.key()).isEqualTo("k1");
            assertThat(meta.filename()).isEqualTo("note.txt");
        });
        assertThat(read(postgres, ref)).isEqualTo("returning");
    }

    @Test
    void aPrefixCoversEveryNamespaceUnderIt() {
        postgres.put("plugin/wiki", "k1", bytes("1"), "text/plain", null, null);
        postgres.put("plugin/bingo", "k1", bytes("2"), "text/plain", null, null);
        FilesystemBlobStore target = filesystem();

        // 'plugin' is what an operator writes in mosaicast.blobs.namespaces, so it is what they should be
        // able to write here — the set of plugin namespaces is the backend's to know, not theirs to list.
        new BlobMigrator(postgres, target, message -> { }).run("plugin", false, false);

        assertThat(target.namespacesUnder("plugin"))
                .contains("plugin/wiki", "plugin/bingo");
    }

    @Test
    void refusesTheNamespaceThatCannotMove() {
        // site_config.*_asset_id are foreign keys into the Postgres blob table and BrandingStorageCheck
        // fails startup when branding is routed elsewhere — moving it produces an install that cannot boot.
        assertThatThrownBy(() -> new BlobMigrator(postgres, filesystem(), message -> { })
                .run("branding", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branding");
    }

    @Test
    void aDryRunWritesNothing() {
        postgres.put("plugin/dry", "k1", bytes("untouched"), "text/plain", null, null);
        FilesystemBlobStore target = filesystem();

        BlobMigrator.Result result =
                new BlobMigrator(postgres, target, message -> { }).run("plugin/dry", true, true);

        assertThat(result.copied()).isZero();
        assertThat(result.deleted()).isZero();
        assertThat(target.namespacesUnder("plugin/dry")).isEmpty();
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(dev.mosaicast.core.blob.BlobStore store, BlobRef ref) {
        try (InputStream stream = store.get(ref).stream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
