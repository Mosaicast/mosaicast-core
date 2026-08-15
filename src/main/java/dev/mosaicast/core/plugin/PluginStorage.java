// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A plugin's {@code storage} declaration (ARCHITECTURE §7.2/§7.6), in either form the manifest allows:
 *
 * <pre>{@code
 * "storage": "doc"
 * "storage": { "schema": { "page": { "slug": "string:indexed:unique", "title": "string" } } }
 * }</pre>
 *
 * <p>Two shapes for one field is unusual, and it is the spec's shape rather than a convenience: the doc
 * store is the default and says nothing more than "doc", while a schema declaration <em>is</em> the
 * entities. Modelling it as one type keeps the either/or in a place that can be reasoned about, instead of
 * a {@code String} plus a parallel field that only makes sense when the string says {@code schema}.
 *
 * @param kind   {@link #DOC} or {@link #SCHEMA}; never null
 * @param schema entity name → (field name → type spec) when {@code kind} is {@link #SCHEMA}; empty for doc
 */
@JsonDeserialize(using = PluginStorageDeserializer.class)
public record PluginStorage(String kind, Map<String, Map<String, String>> schema) {

    /** The generic JSONB doc store — the v1 default, and what a manifest saying nothing gets. */
    public static final String DOC = "doc";

    /** Platform-provisioned relational tables, declared entity by entity (§7.6). */
    public static final String SCHEMA = "schema";

    /** Canonical constructor; normalizes a null schema to empty so callers never null-check it. */
    public PluginStorage {
        schema = schema == null ? Map.of() : Map.copyOf(schema);
    }

    /** The doc-store declaration — also what an absent {@code storage} key means. */
    public static PluginStorage doc() {
        return new PluginStorage(DOC, Map.of());
    }

    /** A schema declaration over the given entities. */
    public static PluginStorage schema(Map<String, Map<String, String>> entities) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        entities.forEach((entity, fields) -> copy.put(entity, Map.copyOf(fields)));
        return new PluginStorage(SCHEMA, copy);
    }

    /** Whether this plugin gets a {@code SchemaStore} at all ({@code ctx.schema()} is null otherwise). */
    public boolean declaresSchema() {
        return SCHEMA.equals(kind) && !schema.isEmpty();
    }

    /** The JSON this declaration came from, for a hash that detects a changed declaration between boots. */
    public static PluginStorage fromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return doc();
        }
        if (node.isString()) {
            String text = node.stringValue().trim().toLowerCase(java.util.Locale.ROOT);
            // A bare "schema" string is the old shape, and it declares no entities — meaningless rather than
            // merely unsupported. Kept as SCHEMA so validate() can say so in those words.
            return new PluginStorage(SCHEMA.equals(text) ? SCHEMA : DOC, Map.of());
        }
        JsonNode schemaNode = node.get(SCHEMA);
        if (schemaNode == null || !schemaNode.isObject()) {
            return doc();
        }
        Map<String, Map<String, String>> entities = new LinkedHashMap<>();
        schemaNode.propertyNames().forEach(entity -> {
            JsonNode fieldsNode = schemaNode.get(entity);
            Map<String, String> fields = new LinkedHashMap<>();
            if (fieldsNode != null && fieldsNode.isObject()) {
                fieldsNode.propertyNames().forEach(field -> fields.put(field, fieldsNode.get(field).asString()));
            }
            entities.put(entity, fields);
        });
        return new PluginStorage(SCHEMA, entities);
    }
}
