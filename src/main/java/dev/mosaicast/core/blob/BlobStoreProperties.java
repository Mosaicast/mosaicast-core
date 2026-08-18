// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which backend stores which namespace (ARCHITECTURE §11, {@code mosaicast.blobs.*}).
 *
 * <p>§11 has always said {@code branding/*} may stay in Postgres forever while {@code audio/*} moves to
 * S3/CDN — but the router was constructed with an empty map and no way to fill it, so "one backend per
 * namespace" was a shape rather than a setting. This is the setting.
 *
 * <pre>{@code
 * mosaicast:
 *   blobs:
 *     default-backend: postgres
 *     namespaces:
 *       plugin: s3          # every plugin's files, by prefix
 *       plugin/wiki: s3     # or just one plugin's, more specific wins
 *       branding: postgres
 * }</pre>
 *
 * <p>Keys are matched by {@link BlobStoreRouter} as exact namespaces first, then by longest {@code /}
 * prefix — so {@code plugin} covers every plugin without naming them, which matters because an operator
 * cannot enumerate plugins that are not installed yet.
 *
 * <p><strong>Today the only registrable name is {@code postgres}</strong>, and a name with no backend behind
 * it fails at startup rather than silently falling through to the default. A routing rule that points
 * nowhere is a misconfiguration whose symptom would otherwise be files quietly landing in the wrong store.
 *
 * @param defaultBackend  the backend for any namespace no rule matches
 * @param namespaces      namespace (or prefix) → backend name
 */
@ConfigurationProperties(prefix = "mosaicast.blobs")
public record BlobStoreProperties(
        @DefaultValue("postgres") String defaultBackend,
        Map<String, String> namespaces) {

    /** Normalises an absent {@code namespaces} block to an empty map, which is the common configuration. */
    public BlobStoreProperties {
        namespaces = namespaces == null ? Map.of() : Map.copyOf(namespaces);
    }

    /** The routing rules, normalised: trimmed, lower-cased names, no empty entries. */
    public Map<String, String> rules() {
        Map<String, String> rules = new LinkedHashMap<>();
        namespaces.forEach((namespace, backend) -> {
            if (namespace != null && backend != null && !namespace.isBlank() && !backend.isBlank()) {
                rules.put(trimSlashes(namespace), backend.trim().toLowerCase(java.util.Locale.ROOT));
            }
        });
        return Map.copyOf(rules);
    }

    /** The default backend name, normalised. */
    public String defaultBackendName() {
        return defaultBackend.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** A namespace key written as {@code /plugin/} or {@code plugin/} means the same as {@code plugin}. */
    private static String trimSlashes(String namespace) {
        String trimmed = namespace.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
