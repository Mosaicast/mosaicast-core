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

    /**
     * The backend for a namespace: an exact registration first, then the longest {@code /}-separated
     * prefix, then the default.
     *
     * <p>Prefix matching exists because namespaces became hierarchical the moment plugins got one:
     * {@code plugin/wiki} and {@code plugin/stats} are different namespaces that an operator would want to
     * route together, and {@code audio/*} is the same shape (§11 names it in exactly those terms). An
     * exact-map lookup would have needed one registration per installed plugin, which is a thing nobody can
     * configure ahead of time. Longest-prefix rather than first-match so a specific registration can still
     * win over a general one.
     *
     * @param namespace the namespace being addressed
     * @return the backend that owns it; never {@code null}
     */
    private BlobStore route(String namespace) {
        BlobStore exact = byNamespace.get(namespace);
        if (exact != null) {
            return exact;
        }
        String candidate = namespace;
        for (int slash = candidate.lastIndexOf('/'); slash > 0; slash = candidate.lastIndexOf('/')) {
            candidate = candidate.substring(0, slash);
            BlobStore prefixed = byNamespace.get(candidate);
            if (prefixed != null) {
                return prefixed;
            }
        }
        return defaultStore;
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime) {
        return route(namespace).put(namespace, key, data, mime);
    }

    @Override
    public BlobRef put(String namespace, String key, InputStream data, String mime, String filename,
                       java.util.UUID uploader) {
        return route(namespace).put(namespace, key, data, mime, filename, uploader);
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
