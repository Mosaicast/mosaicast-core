// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the tables a plugin's schema declaration asks for (ARCHITECTURE §7.6).
 *
 * <p><strong>Why this exists instead of Flyway.</strong> Flyway applies a fixed set of scripts shipped with
 * the release; a plugin's tables are neither fixed nor shipped with it. So the DDL is applied
 * programmatically, and {@code plugin_schema_table} is this runner's record of what it provisioned — see
 * that migration's header for why a name-prefix match would not do.
 *
 * <p><strong>The plugin never writes DDL.</strong> Every identifier here is either a constant, or a name
 * that {@link PluginSchemaValidator} has already matched against {@link SchemaField#NAME} — a pattern with
 * no quote, space or hyphen in it — and every one is emitted double-quoted. Nothing a plugin author types
 * reaches the database as SQL text. Values never appear in DDL at all.
 *
 * <p><strong>Additive only.</strong> A new field is added; a field that disappears from the manifest leaves
 * its column in place, and a field whose declared type changed refuses the plugin at load. Dropping or
 * retyping a column destroys data that a plugin's own users put there, and doing it automatically because a
 * manifest changed between two boots is not a decision this runner gets to make on an admin's behalf.
 */
@Service
public class PluginSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(PluginSchemaMigrator.class);

    private final JdbcTemplate jdbc;

    public PluginSchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Brings the database in line with a plugin's declaration, creating what is missing.
     *
     * @param pluginId the plugin's manifest id
     * @param entities the resolved entities ({@link PluginManifest#schemaEntities()})
     * @throws PluginValidationException if an existing column's type no longer matches the declaration
     */
    @Transactional
    public void provision(String pluginId, Map<String, PluginSchemaValidator.Entity> entities) {
        if (entities.isEmpty()) {
            return;
        }
        for (PluginSchemaValidator.Entity entity : entities.values()) {
            provisionEntity(pluginId, entity);
        }
        log.info("Provisioned {} schema table(s) for plugin '{}'", entities.size(), pluginId);
    }

    private void provisionEntity(String pluginId, PluginSchemaValidator.Entity entity) {
        String table = entity.tableName();
        if (!tableExists(table)) {
            create(entity);
        } else {
            addMissingColumns(entity);
        }
        createIndexes(entity);

        // Upsert the bookkeeping row: re-running a boot must not fail, and the table name is the thing purge
        // will need after the plugin folder is gone.
        jdbc.update("""
                insert into plugin_schema_table (plugin_id, entity, table_name)
                values (?, ?, ?)
                on conflict (plugin_id, entity) do update set table_name = excluded.table_name
                """, pluginId, entity.name(), table);
    }

    private void create(PluginSchemaValidator.Entity entity) {
        String columns = entity.fields().values().stream()
                .map(field -> "%s %s".formatted(quote(field.name()), field.type().sqlType()))
                .collect(Collectors.joining(", "));
        // `id bigserial primary key` is the platform-assigned id every entity carries (§7.6).
        jdbc.execute("create table %s (id bigserial primary key, %s)".formatted(quote(entity.tableName()), columns));
        log.info("Created schema table {}", entity.tableName());
    }

    /**
     * Adds columns the declaration gained since the table was created, and refuses a changed type.
     *
     * <p>A retype is refused rather than applied because the safe conversions are a small subset of the
     * possible ones, and the unsafe ones lose a plugin's users' data silently. Refusing names the problem at
     * load, where an author can fix the manifest or an admin can purge and start again.
     */
    private void addMissingColumns(PluginSchemaValidator.Entity entity) {
        Map<String, String> existing = existingColumns(entity.tableName());
        for (SchemaField field : entity.fields().values()) {
            String actual = existing.get(field.name().toLowerCase(Locale.ROOT));
            if (actual == null) {
                jdbc.execute("alter table %s add column %s %s"
                        .formatted(quote(entity.tableName()), quote(field.name()), field.type().sqlType()));
                log.info("Added column {}.{}", entity.tableName(), field.name());
                continue;
            }
            if (!actual.equalsIgnoreCase(expectedUdt(field))) {
                throw new PluginValidationException(
                        ("schema field '%s' of entity '%s' is declared %s but the existing column is %s; "
                                + "the host does not retype a column that already holds data — purge the "
                                + "plugin's data to re-provision it")
                                .formatted(field.name(), entity.name(),
                                        field.type().name().toLowerCase(Locale.ROOT), actual));
            }
        }
    }

    private void createIndexes(PluginSchemaValidator.Entity entity) {
        for (SchemaField field : entity.fields().values()) {
            if (field.unique()) {
                jdbc.execute("create unique index if not exists %s on %s (%s)".formatted(
                        quote(indexName(entity.tableName(), field.name(), "uq")),
                        quote(entity.tableName()), quote(field.name())));
            } else if (field.indexed()) {
                jdbc.execute("create index if not exists %s on %s (%s)".formatted(
                        quote(indexName(entity.tableName(), field.name(), "ix")),
                        quote(entity.tableName()), quote(field.name())));
            }
            if (field.fulltext()) {
                // A GIN index over to_tsvector, the same shape episode search uses (V2). 'simple' rather
                // than a language configuration: a plugin's content language is not something the manifest
                // declares, and guessing English would stem other languages wrongly.
                jdbc.execute("create index if not exists %s on %s using gin (to_tsvector('simple', coalesce(%s, '')))"
                        .formatted(quote(indexName(entity.tableName(), field.name(), "fts")),
                                quote(entity.tableName()), quote(field.name())));
            }
        }
    }

    /**
     * Drops everything a plugin's schema declaration provisioned.
     *
     * <p>Called by purge only (§7.8): a removed plugin goes dormant with its data intact, and only an
     * explicit admin action deletes it. The table list comes from the bookkeeping rows rather than from a
     * name pattern — a {@code drop table} chosen by prefix match is one naming accident away from taking
     * something else with it.
     *
     * @return how many tables were dropped
     */
    @Transactional
    public int purge(String pluginId) {
        List<String> tables = jdbc.queryForList(
                "select table_name from plugin_schema_table where plugin_id = ?", String.class, pluginId);
        for (String table : tables) {
            // Quoted, and every one of these was written by this class from a validated name.
            jdbc.execute("drop table if exists %s cascade".formatted(quote(table)));
        }
        jdbc.update("delete from plugin_schema_table where plugin_id = ?", pluginId);
        if (!tables.isEmpty()) {
            log.info("Dropped {} schema table(s) of plugin '{}': {}", tables.size(), pluginId, tables);
        }
        return tables.size();
    }

    /** The tables currently provisioned for a plugin — for the admin view and for tests. */
    public List<String> tablesOf(String pluginId) {
        return jdbc.queryForList(
                "select table_name from plugin_schema_table where plugin_id = ? order by entity",
                String.class, pluginId);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = current_schema() and table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }

    /** Existing column names (lower-cased) to their Postgres type, for the additive comparison. */
    private Map<String, String> existingColumns(String table) {
        return jdbc.query("""
                select column_name, udt_name from information_schema.columns
                where table_schema = current_schema() and table_name = ?
                """, rs -> {
            Map<String, String> columns = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                columns.put(rs.getString("column_name").toLowerCase(Locale.ROOT), rs.getString("udt_name"));
            }
            return columns;
        }, table);
    }

    /** What {@code information_schema.columns.udt_name} reports for a declared type. */
    private static String expectedUdt(SchemaField field) {
        return switch (field.type()) {
            case STRING, TEXT -> "text";
            case INTEGER -> "int8";
            case NUMBER -> "float8";
            case BOOLEAN -> "bool";
            case TIMESTAMP -> "timestamptz";
        };
    }

    /**
     * An index name that fits Postgres's 63-byte limit.
     *
     * <p>Truncation is silent, and two truncated-alike names would make the second {@code create index if
     * not exists} a no-op — an index the declaration asked for and never got. Trimming the table part first
     * keeps the field name, which is what makes two indexes on one table distinguishable.
     */
    private static String indexName(String table, String field, String suffix) {
        String name = "%s_%s_%s".formatted(table, field.toLowerCase(Locale.ROOT), suffix);
        if (name.length() <= 63) {
            return name;
        }
        int room = 63 - field.length() - suffix.length() - 2;
        return "%s_%s_%s".formatted(table.substring(0, Math.max(1, room)), field.toLowerCase(Locale.ROOT), suffix);
    }

    /**
     * Quotes an identifier.
     *
     * <p>Every caller passes either a constant or a name already matched against {@link SchemaField#NAME},
     * which cannot contain a quote — so this preserves camelCase (Postgres would fold it otherwise) rather
     * than being the thing that makes the statement safe. The assertion is here so that stays true if
     * someone later routes an unchecked name through it.
     */
    private static String quote(String identifier) {
        if (identifier == null || identifier.indexOf('"') >= 0) {
            throw new IllegalArgumentException("Refusing to quote an unchecked identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    /** Exposed for the store, which builds statements against the same rules. */
    static String quoted(String identifier) {
        return quote(identifier);
    }
}
