// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * One declared field of a schema entity, parsed from its manifest type spec (ARCHITECTURE §7.6):
 * {@code <type>[:<modifier>]*}, e.g. {@code string:indexed:unique}, {@code text:fulltext},
 * {@code timestamp:indexed}.
 *
 * <p><strong>This grammar is the whole boundary between a manifest and DDL.</strong> A plugin never writes
 * DDL and never names a table; what it does supply is a field name and a type spec, and both are turned
 * into SQL by the host. So both are checked here against closed sets — a fixed list of types, a fixed list
 * of modifiers, and a name pattern with no quote character in it — and anything outside them refuses the
 * plugin at load rather than being coerced into something that runs.
 *
 * <p>Names permit camelCase because the SDK's own example declares {@code updatedAt} and rows map onto
 * record components of the same name. Postgres folds an unquoted identifier to lower case, which would
 * turn that into {@code updatedat} and quietly break the mapping — so every generated identifier is
 * double-quoted. That is safe precisely because {@link #NAME} cannot match a name containing a quote.
 */
public record SchemaField(String name, Type type, Set<String> modifiers) {

    /** What a declared field may be. The SQL type is the host's choice, not the plugin's. */
    public enum Type {

        /** Short text — a slug, a title. */
        STRING("text"),

        /** Long text; the only type {@code :fulltext} may be applied to. */
        TEXT("text"),

        /** Whole numbers. {@code bigint} rather than {@code integer}: the range is free. */
        INTEGER("bigint"),

        /** Fractional numbers. */
        NUMBER("double precision"),

        /** True/false. */
        BOOLEAN("boolean"),

        /** An instant, stored with a time zone so it round-trips as {@link java.time.Instant}. */
        TIMESTAMP("timestamptz");

        private final String sqlType;

        Type(String sqlType) {
            this.sqlType = sqlType;
        }

        /** The Postgres column type this maps to. */
        public String sqlType() {
            return sqlType;
        }
    }

    /** Give the column an index. */
    public static final String INDEXED = "indexed";

    /** Require values to be unique across the entity. Implies an index. */
    public static final String UNIQUE = "unique";

    /** Provision a full-text index, making the field usable with {@code SchemaStore.search}. */
    public static final String FULLTEXT = "fulltext";

    private static final Set<String> MODIFIERS = Set.of(INDEXED, UNIQUE, FULLTEXT);

    /**
     * What an entity or field may be called: a letter, then letters, digits or underscores.
     *
     * <p>The length cap leaves room inside Postgres's 63-byte identifier limit once
     * {@code plugin_<id>_<entity>} and index suffixes are built on top — see
     * {@link PluginSchemaValidator}. No quote, no space, no hyphen: nothing that could end a quoted
     * identifier or start something else.
     */
    public static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    /**
     * Parses a manifest type spec.
     *
     * @param name the declared field name
     * @param spec the type spec, e.g. {@code string:indexed:unique}
     * @return the parsed field
     * @throws PluginValidationException if the name, the type or any modifier is not one this host knows
     */
    public static SchemaField parse(String name, String spec) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new PluginValidationException(
                    "schema field name '%s' must match %s".formatted(name, NAME.pattern()));
        }
        if (name.equalsIgnoreCase("id")) {
            // Every entity carries a platform-assigned id (§7.6 / the SDK's SchemaStore contract). A
            // declared one would either collide with it or shadow it, and both are worse than saying no.
            throw new PluginValidationException(
                    "schema field 'id' is assigned by the platform and must not be declared");
        }
        if (spec == null || spec.isBlank()) {
            throw new PluginValidationException("schema field '" + name + "' declares no type");
        }

        String[] parts = spec.trim().toLowerCase(Locale.ROOT).split(":");
        Type type;
        try {
            type = Type.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PluginValidationException(
                    "schema field '%s' declares unknown type '%s'; known types: %s"
                            .formatted(name, parts[0], knownTypes()));
        }

        Set<String> modifiers = new LinkedHashSet<>();
        for (int i = 1; i < parts.length; i++) {
            String modifier = parts[i].trim();
            if (!MODIFIERS.contains(modifier)) {
                throw new PluginValidationException(
                        "schema field '%s' declares unknown modifier '%s'; known modifiers: %s"
                                .formatted(name, modifier, MODIFIERS));
            }
            modifiers.add(modifier);
        }

        if (modifiers.contains(FULLTEXT) && type != Type.TEXT && type != Type.STRING) {
            throw new PluginValidationException(
                    "schema field '%s' is :fulltext but declared %s; only string and text can be searched"
                            .formatted(name, parts[0]));
        }
        return new SchemaField(name, type, Set.copyOf(modifiers));
    }

    /** Whether this field needs a plain index (unique implies one, and is created separately). */
    public boolean indexed() {
        return modifiers.contains(INDEXED) && !modifiers.contains(UNIQUE);
    }

    public boolean unique() {
        return modifiers.contains(UNIQUE);
    }

    public boolean fulltext() {
        return modifiers.contains(FULLTEXT);
    }

    private static String knownTypes() {
        return java.util.Arrays.stream(Type.values())
                .map(t -> t.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
