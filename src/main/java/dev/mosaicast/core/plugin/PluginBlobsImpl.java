// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.BlobInfo;
import dev.mosaicast.plugin.api.BlobQuota;
import dev.mosaicast.plugin.api.PluginBlobs;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The {@link PluginBlobs} a plugin backend gets on {@code ctx.blobs()} (ARCHITECTURE §11).
 *
 * <p>Hard-scoped the same way {@link DocStoreImpl} and {@code SchemaStoreImpl} are: the plugin id is fixed
 * at construction and every call derives the namespace from it, so a backend cannot name another plugin's
 * file — not because it is checked, but because there is no parameter for it.
 *
 * <p><strong>No caller, so no uploader.</strong> A backend thread is not a request: files it stores are
 * attributed to nobody, which is the honest answer and the same reason {@code DocStoreImpl} refuses the
 * {@code USER} scope outright.
 *
 * <p>Refusals arrive as {@link IllegalArgumentException}, per the SDK's contract — a plugin author sees the
 * same failure against the host as against {@code InMemoryPluginBlobs} in the test kit, rather than a
 * host-specific exception type they cannot catch without depending on core.
 */
final class PluginBlobsImpl implements PluginBlobs {

    private final PluginManifest manifest;
    private final PluginBlobService blobs;

    PluginBlobsImpl(PluginManifest manifest, PluginBlobService blobs) {
        this.manifest = manifest;
        this.blobs = blobs;
    }

    @Override
    public BlobInfo put(String filename, String mime, InputStream data) {
        try {
            // Size is unknown up front for a stream, so -1: the service reads with its own ceiling and
            // refuses past it, which is the check that matters anyway.
            return blobs.put(manifest, filename, mime, -1L, data, null);
        } catch (BlobQuotaExceededException | BlobTypeNotAllowedException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<BlobInfo> stat(String ref) {
        return blobs.stat(manifest.id(), ref);
    }

    @Override
    public InputStream open(String ref) {
        return blobs.open(manifest.id(), ref, -1L, -1L)
                .orElseThrow(() -> new IllegalArgumentException("no such blob: " + ref))
                .stream();
    }

    @Override
    public boolean delete(String ref) {
        return blobs.delete(manifest.id(), ref);
    }

    @Override
    public List<BlobInfo> list(int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("page must be >= 0 and size > 0");
        }
        return blobs.list(manifest.id(), page, size);
    }

    @Override
    public String urlFor(String ref) {
        return PluginBlobService.urlFor(manifest.id(), ref);
    }

    @Override
    public BlobQuota quota() {
        return blobs.quota(manifest);
    }
}
