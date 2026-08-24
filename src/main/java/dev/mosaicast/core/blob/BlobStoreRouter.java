// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BlobStoreRouter.class);

    private final NamedBlobStore defaultStore;
    private final Map<String, NamedBlobStore> byNamespace;

    /**
     * Wires the routing table from configuration.
     *
     * <p>Every {@link NamedBlobStore} on the context registers itself by name, and the properties say which
     * namespace goes to which name. Today that is a table with one entry in it — {@code postgres} — and the
     * point is not the flexibility but the seam: adding a filesystem or object-store backend becomes a new
     * bean and a config line rather than a change here.
     *
     * <p><strong>An unknown backend name fails startup.</strong> Falling back to the default would mean a
     * typo in a routing rule silently sends a plugin's files somewhere the operator did not choose, and the
     * symptom — files in the wrong store — appears long after the cause.
     *
     * @param backends   every registered backend, by name
     * @param properties the routing rules
     */
    public BlobStoreRouter(List<NamedBlobStore> backends, BlobStoreProperties properties) {
        Map<String, NamedBlobStore> registry = new LinkedHashMap<>();
        backends.forEach(backend -> registry.put(backend.backendName(), backend));

        String defaultName = properties.defaultBackendName();
        this.defaultStore = require(registry, defaultName, "mosaicast.blobs.default-backend");

        Map<String, NamedBlobStore> routes = new LinkedHashMap<>();
        properties.rules().forEach((namespace, name) ->
                routes.put(namespace, require(registry, name, "mosaicast.blobs.namespaces." + namespace)));
        this.byNamespace = Map.copyOf(routes);

        if (!byNamespace.isEmpty()) {
            log.info("Blob namespaces routed away from '{}': {}", defaultName, byNamespace.keySet());
        }
    }

    private static NamedBlobStore require(Map<String, NamedBlobStore> registry, String name, String setting) {
        NamedBlobStore backend = registry.get(name);
        if (backend == null) {
            throw new IllegalStateException(
                    "%s names blob backend '%s', which is not registered; available: %s"
                            .formatted(setting, name, registry.keySet()));
        }
        return backend;
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
    private NamedBlobStore route(String namespace) {
        NamedBlobStore exact = byNamespace.get(namespace);
        if (exact != null) {
            return exact;
        }
        String candidate = namespace;
        for (int slash = candidate.lastIndexOf('/'); slash > 0; slash = candidate.lastIndexOf('/')) {
            candidate = candidate.substring(0, slash);
            NamedBlobStore prefixed = byNamespace.get(candidate);
            if (prefixed != null) {
                return prefixed;
            }
        }
        return defaultStore;
    }

    /**
     * Which backend a namespace resolves to, by name — for a component that has a constraint about
     * <em>where</em> its blobs live rather than merely how to reach them.
     *
     * <p>Exists for exactly one caller today ({@code BrandingStorageCheck}), and that is the honest shape:
     * the router knows the routing, and the constraint belongs to whoever has it.
     *
     * @param namespace the namespace
     * @return the resolved backend's name
     */
    public String backendNameFor(String namespace) {
        return route(namespace).backendName();
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
    public Optional<String> directUrl(BlobRef ref, AccessContext ctx) {
        return route(ref.namespace()).directUrl(ref, ctx);
    }

    @Override
    public java.util.List<BlobMetadata> list(String namespace, int page, int size) {
        return route(namespace).list(namespace, page, size);
    }

    @Override
    public long count(String namespace) {
        return route(namespace).count(namespace);
    }

    @Override
    public long usedBytes(String namespace) {
        return route(namespace).usedBytes(namespace);
    }

    @Override
    public int deleteNamespace(String namespace) {
        return route(namespace).deleteNamespace(namespace);
    }

    @Override
    public BlobCapabilities capabilities() {
        // v1: a single backend for all namespaces.
        return defaultStore.capabilities();
    }

    /**
     * Routed like any other write — but note what this means for a migration: through the router, source
     * and target of a move would be the <em>same</em> backend, since routing is by namespace and the
     * namespace does not change. {@code BlobMigrator} therefore addresses backends directly, which is why
     * it takes their names rather than a direction.
     */
    @Override
    public BlobRef putVerbatim(BlobMetadata metadata, InputStream data) {
        return route(metadata.ref().namespace()).putVerbatim(metadata, data);
    }

    @Override
    public java.util.List<String> namespacesUnder(String prefix) {
        return route(prefix).namespacesUnder(prefix);
    }
}
