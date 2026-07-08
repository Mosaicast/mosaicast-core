// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Routes blob operations to a backend per namespace (ARCHITECTURE §11). In v1 everything maps to Postgres;
 * this is the seam where {@code audio/*} moves to S3/CDN later without touching callers. Injected as the
 * primary {@link BlobStore}.
 */
@Component
@Primary
public class BlobStoreRouter implements BlobStore {

    private final BlobStore defaultStore;
    private final Map<String, BlobStore> byNamespace;

    public BlobStoreRouter(PostgresBlobStore postgres) {
        this.defaultStore = postgres;
        // v1: no per-namespace overrides — branding and everything else live in Postgres.
        this.byNamespace = Map.of();
    }

    private BlobStore route(String namespace) {
        return byNamespace.getOrDefault(namespace, defaultStore);
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return route(namespace).put(namespace, key, data, mime);
    }

    @Override
    public Optional<BlobMetadata> stat(String namespace, String key) {
        return route(namespace).stat(namespace, key);
    }

    @Override
    public Optional<BlobMetadata> stat(BlobRef ref) {
        return route(ref.namespace()).stat(ref);
    }

    @Override
    public BlobContent get(BlobRef ref) {
        return route(ref.namespace()).get(ref);
    }

    @Override
    public BlobContent getRange(BlobRef ref, long start, long endInclusive) {
        return route(ref.namespace()).getRange(ref, start, endInclusive);
    }

    @Override
    public void delete(BlobRef ref) {
        route(ref.namespace()).delete(ref);
    }

    @Override
    public String urlFor(BlobRef ref, AccessContext ctx) {
        return route(ref.namespace()).urlFor(ref, ctx);
    }

    @Override
    public BlobCapabilities capabilities() {
        // v1: a single backend for all namespaces.
        return defaultStore.capabilities();
    }
}
