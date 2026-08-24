// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob.migrate;

import dev.mosaicast.core.blob.BlobMetadata;
import dev.mosaicast.core.blob.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Moves a namespace's blobs from one backend to another (ARCHITECTURE §11, issue #105).
 *
 * <p>Routing a namespace at a different backend does nothing to what is already stored: the old backend
 * keeps the bytes, the new one starts empty, so every existing ref 404s and the plugin's quota reads as
 * zero while the source is still full. This is the other half of that switch.
 *
 * <p><strong>It works through {@link BlobStore}, which is the point.</strong> An external tool would have
 * to re-implement each backend's layout from outside — the sidecar's fields, the table's columns, the
 * safety rules — and that copy is correct until someone adds a backend or changes a field, with nothing to
 * say otherwise. Here a new backend is migratable the day it implements the interface, and the format is
 * known in exactly one place.
 *
 * <p>Three properties worth stating, because each is a way this could destroy data and does not:
 *
 * <ul>
 *   <li><strong>Ids cross unchanged</strong> ({@link BlobStore#putVerbatim}). An id is the identity a
 *       plugin stores, so renumbering during a copy would orphan every reference it ever saved.</li>
 *   <li><strong>Nothing is deleted until everything verified.</strong> Each object is read back from the
 *       target and compared by SHA-256; a mismatch aborts with the source intact.</li>
 *   <li><strong>Re-running is safe.</strong> An object already at the target with a matching hash is
 *       skipped, so an interrupted run continues rather than being something to unpick.</li>
 * </ul>
 */
public class BlobMigrator {

    /**
     * The namespace that cannot move: {@code site_config.{logo,favicon,dark_logo}_asset_id} are foreign
     * keys into the Postgres {@code blob} table, and {@code BrandingStorageCheck} fails startup when this
     * namespace is routed elsewhere. Moving it would produce an install that does not boot, so it is
     * refused before anything is read rather than discovered at the far end.
     */
    public static final String PINNED_TO_POSTGRES = "branding";

    private final BlobStore source;
    private final BlobStore target;
    private final Consumer<String> out;

    public BlobMigrator(BlobStore source, BlobStore target, Consumer<String> out) {
        this.source = source;
        this.target = target;
        this.out = out;
    }

    /**
     * Copies everything under {@code prefix}, and optionally clears the source afterwards.
     *
     * @param prefix       a namespace or a {@code /}-separated prefix — {@code plugin} covers every
     *                     {@code plugin/<id>} the source holds
     * @param deleteSource whether to remove the objects from the source once every one has verified
     * @param dryRun       list what would move and write nothing
     * @return what happened, for a caller that wants to assert on it rather than read the log
     */
    public Result run(String prefix, boolean deleteSource, boolean dryRun) {
        String namespace = normalize(prefix);
        if (namespace.equals(PINNED_TO_POSTGRES) || namespace.startsWith(PINNED_TO_POSTGRES + "/")) {
            throw new IllegalArgumentException(
                    ("'%s' cannot be moved: site_config.{logo,favicon,dark_logo}_asset_id are foreign keys "
                            + "into the Postgres blob table, and the app refuses to start when that "
                            + "namespace is routed elsewhere").formatted(PINNED_TO_POSTGRES));
        }

        List<BlobMetadata> objects = new ArrayList<>();
        for (String each : source.namespacesUnder(namespace)) {
            objects.addAll(everythingIn(source, each));
        }
        if (objects.isEmpty()) {
            out.accept("Nothing to move: the source holds no blobs under '%s'.".formatted(namespace));
            return new Result(0, 0, 0);
        }

        long bytes = objects.stream().mapToLong(BlobMetadata::size).sum();
        out.accept("Found %d object(s), %s, under '%s'.".formatted(objects.size(), human(bytes), namespace));
        if (dryRun) {
            objects.forEach(one -> out.accept("  would copy %s  %s/%s  %s".formatted(
                    one.ref().id(), one.ref().namespace(), one.key(), human(one.size()))));
            out.accept("Dry run: nothing was written.");
            return new Result(0, 0, 0);
        }

        Map<String, BlobMetadata> already = objects.stream()
                .map(one -> one.ref().namespace())
                .distinct()
                .flatMap(each -> everythingIn(target, each).stream())
                .collect(Collectors.toMap(one -> one.ref().id().toString(), one -> one, (a, b) -> a));

        int copied = 0;
        int skipped = 0;
        for (BlobMetadata one : objects) {
            String digest = sha256(read(source, one));
            BlobMetadata there = already.get(one.ref().id().toString());
            if (there != null && there.size() == one.size() && sha256(read(target, there)).equals(digest)) {
                skipped++;
                continue;
            }
            try (InputStream data = source.get(one.ref()).stream()) {
                target.putVerbatim(one, data);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot copy " + one.ref().id(), e);
            }
            if (!sha256(read(target, one)).equals(digest)) {
                throw new IllegalStateException(
                        "verification failed for %s — the copy does not match the source, nothing deleted"
                                .formatted(one.ref().id()));
            }
            copied++;
        }
        out.accept("Copied %d, skipped %d already present and verified.".formatted(copied, skipped));

        int deleted = 0;
        if (deleteSource) {
            // Only now: a partial copy followed by a delete is the one outcome there is no way back from.
            for (BlobMetadata one : objects) {
                source.delete(one.ref());
                deleted++;
            }
            out.accept("Deleted %d object(s) from the source.".formatted(deleted));
        } else {
            out.accept(("Source untouched. Flip `mosaicast.blobs.namespaces.%s`, restart, and re-run with "
                    + "--delete-source once you believe it.").formatted(namespace.split("/")[0]));
        }
        return new Result(copied, skipped, deleted);
    }

    /** Everything one backend holds in one namespace, paged so a large namespace does not arrive at once. */
    private static List<BlobMetadata> everythingIn(BlobStore store, String namespace) {
        List<BlobMetadata> found = new ArrayList<>();
        int page = 0;
        while (true) {
            List<BlobMetadata> batch = store.list(namespace, page++, 200);
            if (batch.isEmpty()) {
                return found;
            }
            found.addAll(batch);
            if (batch.size() < 200) {
                return found;
            }
        }
    }

    private static byte[] read(BlobStore store, BlobMetadata meta) {
        try (InputStream stream = store.get(meta.ref()).stream()) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + meta.ref().id(), e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalize(String prefix) {
        String trimmed = prefix == null ? "" : prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a namespace is required, e.g. --namespace plugin");
        }
        return trimmed;
    }

    static String human(long size) {
        if (size < 1024) {
            return size + " B";
        }
        String[] units = {"KiB", "MiB", "GiB"};
        double value = size / 1024.0;
        for (int i = 0; i < units.length; i++) {
            if (value < 1024 || i == units.length - 1) {
                return "%.1f %s".formatted(value, units[i]);
            }
            value /= 1024;
        }
        return size + " B";
    }

    /** What a run did — copied, skipped as already present, deleted from the source. */
    public record Result(int copied, int skipped, int deleted) {
    }
}
