// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a manifest's {@code storage.schema} block into the entity/field model the rest of the host uses,
 * refusing anything it cannot make into safe DDL (ARCHITECTURE §7.6).
 *
 * <p>Runs at load, so a plugin whose declaration is unusable is rejected with a reason rather than starting
 * and failing later — the same rule {@code data.backendOwned} follows, and for the same reason: a storage
 * declaration that the host silently drops leaves a plugin whose code expects tables that do not exist.
 */
public final class PluginSchemaValidator {

    /** Postgres truncates identifiers at 63 bytes; two names that collide after truncation are one table. */
    private static final int MAX_IDENTIFIER = 63;

    /** Room reserved for the longest suffix the runner appends when naming an index. */
    private static final int INDEX_SUFFIX_ROOM = 16;

    private PluginSchemaValidator() {
    }

    /** One declared entity: its table name and its fields, in declaration order. */
    public record Entity(String name, String tableName, Map<String, SchemaField> fields) {
    }

    /**
     * Validates and resolves a plugin's schema declaration.
     *
     * @param pluginId the plugin's manifest id, which becomes part of every table name
     * @param storage  the parsed storage declaration
     * @return entity name → resolved entity, in declaration order; empty when no schema is declared
     * @throws PluginValidationException if anything in the declaration cannot become safe DDL
     */
    public static Map<String, Entity> resolve(String pluginId, PluginStorage storage) {
        if (storage == null || !PluginStorage.SCHEMA.equals(storage.kind())) {
            return Map.of();
        }
        if (storage.schema().isEmpty()) {
            throw new PluginValidationException(
                    "storage declares \"schema\" but no entities; use \"doc\" or declare at least one");
        }
        if (!SchemaField.NAME.matcher(pluginId == null ? "" : pluginId).matches()) {
            // The id becomes part of a table name, so it has to survive the same check a field name does.
            throw new PluginValidationException(
                    "plugin id '%s' cannot be used in a table name; it must match %s"
                            .formatted(pluginId, SchemaField.NAME.pattern()));
        }

        Map<String, Entity> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> declared : storage.schema().entrySet()) {
            String entity = declared.getKey();
            if (!SchemaField.NAME.matcher(entity == null ? "" : entity).matches()) {
                throw new PluginValidationException(
                        "schema entity name '%s' must match %s".formatted(entity, SchemaField.NAME.pattern()));
            }
            if (declared.getValue() == null || declared.getValue().isEmpty()) {
                throw new PluginValidationException("schema entity '" + entity + "' declares no fields");
            }

            String table = tableName(pluginId, entity);
            if (table.length() > MAX_IDENTIFIER - INDEX_SUFFIX_ROOM) {
                // Truncation is silent in Postgres, and two entities that collide after it would share one
                // table — a data-mixing bug that would look like the plugin's own.
                throw new PluginValidationException(
                        "schema entity '%s' makes the table name '%s' too long for Postgres; shorten the "
                                .formatted(entity, table) + "entity or plugin id");
            }

            Map<String, SchemaField> fields = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : declared.getValue().entrySet()) {
                SchemaField parsed = SchemaField.parse(field.getKey(), field.getValue());
                // Postgres folds unquoted identifiers, and the host quotes everything — so `Slug` and `slug`
                // would be two columns that a case-insensitive reader would confuse. Refuse the ambiguity.
                for (String existing : fields.keySet()) {
                    if (existing.equalsIgnoreCase(parsed.name())) {
                        throw new PluginValidationException(
                                "schema entity '%s' declares '%s' and '%s', which differ only in case"
                                        .formatted(entity, existing, parsed.name()));
                    }
                }
                fields.put(parsed.name(), parsed);
            }
            resolved.put(entity, new Entity(entity, table, fields));
        }
        return resolved;
    }

    /** The table one entity is provisioned into: {@code plugin_<pluginId>_<entity>}, lower-cased. */
    public static String tableName(String pluginId, String entity) {
        return ("plugin_" + pluginId + "_" + entity).toLowerCase(java.util.Locale.ROOT);
    }

    /** The prefix every table of one plugin shares — what {@code SchemaStore.namespace()} reports. */
    public static String namespace(String pluginId) {
        return ("plugin_" + pluginId + "_").toLowerCase(java.util.Locale.ROOT);
    }
}
