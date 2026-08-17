// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Criteria;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the query string of the plugin schema surface into a {@link Criteria} (ARCHITECTURE §7.6).
 *
 * <p>The wire grammar, deliberately small — a client describes a query, it never writes one:
 *
 * <pre>{@code
 * where=field:op:value        repeatable; ops eq ne lt lte gt gte like in isnull isnotnull
 * orderBy=field:asc|desc      repeatable; the direction may be omitted and defaults to asc
 * }</pre>
 *
 * <p><strong>This is schema-aware because it has to be.</strong> Every value arrives as text and JDBC binds
 * by column type, so {@code views:gte:30} has to become a {@code Long} before it reaches
 * {@link SchemaStoreImpl} — a {@code String} bound to a {@code bigint} is a driver error, i.e. a 500 for
 * what is really a malformed request. So each value is coerced against the field's <em>declared</em>
 * {@link SchemaField.Type}, and a value that cannot be read as that type is refused here.
 *
 * <p>Everything this class refuses throws {@link IllegalArgumentException}, which the API exception handler
 * answers <strong>400</strong> with. That includes an undeclared field name: the name resolution is
 * {@link PluginSchemaValidator.Entity#field(String)}, the same call the store makes, so the surface and the
 * store refuse the same names with the same wording. What it does <em>not</em> do is decide access or
 * resolve the entity — that is the controller's, before this runs.
 *
 * <p><strong>Two limits worth knowing.</strong> A term is split on its first two colons, so a value may
 * contain colons ({@code updatedAt:gte:2026-01-01T00:00:00Z} works). An {@code in} list is comma-separated
 * with no escaping, so a string value containing a comma cannot be expressed — use repeated {@code eq}
 * terms, or ask for the grammar to grow.
 */
final class SchemaQueryParams {

    private SchemaQueryParams() {
    }

    /**
     * Parses the filter and ordering of one request.
     *
     * <p>Paging is not applied here: {@code count} shares the predicates and ignores the rest, so the
     * caller adds {@code limit}/{@code offset} where they mean something.
     *
     * @param entity  the resolved entity every name is checked against
     * @param where   the raw {@code where} terms, possibly empty or {@code null}
     * @param orderBy the raw {@code orderBy} terms, possibly empty or {@code null}
     * @return the criteria; {@link Criteria#all()} when nothing was asked for
     * @throws IllegalArgumentException on an unknown field, an unknown operator, a missing or unreadable
     *                                  value — everything the host answers 400 for
     */
    static Criteria parse(PluginSchemaValidator.Entity entity, List<String> where, List<String> orderBy) {
        Criteria criteria = Criteria.all();
        for (String term : where == null ? List.<String>of() : where) {
            criteria = predicate(entity, criteria, term);
        }
        for (String term : orderBy == null ? List.<String>of() : orderBy) {
            criteria = order(entity, criteria, term);
        }
        return criteria;
    }

    /** Adds one {@code field:op:value} term. */
    private static Criteria predicate(PluginSchemaValidator.Entity entity, Criteria criteria, String term) {
        // Limit 3: the value keeps every colon after the operator, which is what makes a timestamp
        // expressible without an escaping rule nobody would remember.
        String[] parts = term == null ? new String[0] : term.split(":", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "where term '%s' must be 'field:op' or 'field:op:value'".formatted(term));
        }
        SchemaField field = entity.field(parts[0]);
        String rawValue = parts.length == 3 ? parts[2] : null;

        return switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "eq" -> criteria.and(field.name(), Criteria.Op.EQ, value(field, rawValue, term));
            case "ne" -> criteria.and(field.name(), Criteria.Op.NE, value(field, rawValue, term));
            case "lt" -> criteria.and(field.name(), Criteria.Op.LT, value(field, rawValue, term));
            case "lte" -> criteria.and(field.name(), Criteria.Op.LTE, value(field, rawValue, term));
            case "gt" -> criteria.and(field.name(), Criteria.Op.GT, value(field, rawValue, term));
            case "gte" -> criteria.and(field.name(), Criteria.Op.GTE, value(field, rawValue, term));
            // LIKE is a pattern, not a value of the field's type: `%` never parses as a number or an
            // instant, so coercing it would refuse the only thing it is for.
            case "like" -> criteria.and(field.name(), Criteria.Op.LIKE, required(rawValue, term));
            case "in" -> criteria.and(field.name(), Criteria.Op.IN, list(field, required(rawValue, term)));
            // A null check has nothing to compare against; a value here means the caller misread the
            // grammar, and accepting it silently would hide that.
            case "isnull" -> nullCheck(criteria, field, Criteria.Op.IS_NULL, rawValue, term);
            case "isnotnull" -> nullCheck(criteria, field, Criteria.Op.IS_NOT_NULL, rawValue, term);
            default -> throw new IllegalArgumentException(
                    ("where term '%s' uses unknown operator '%s'; known operators: eq, ne, lt, lte, gt, "
                            + "gte, like, in, isnull, isnotnull").formatted(term, parts[1]));
        };
    }

    private static Criteria nullCheck(Criteria criteria, SchemaField field, Criteria.Op op,
                                      String rawValue, String term) {
        if (rawValue != null && !rawValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "where term '%s' takes no value: %s compares against nothing".formatted(term, op));
        }
        return criteria.and(field.name(), op, null);
    }

    /** Adds one {@code field[:asc|:desc]} term. */
    private static Criteria order(PluginSchemaValidator.Entity entity, Criteria criteria, String term) {
        String[] parts = term == null ? new String[0] : term.split(":", 2);
        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("orderBy term '%s' names no field".formatted(term));
        }
        SchemaField field = entity.field(parts[0]);
        String direction = parts.length == 2 ? parts[1].toLowerCase(Locale.ROOT) : "asc";
        return switch (direction) {
            case "asc" -> criteria.orderBy(field.name(), Criteria.Direction.ASC);
            case "desc" -> criteria.orderBy(field.name(), Criteria.Direction.DESC);
            default -> throw new IllegalArgumentException(
                    "orderBy term '%s' must end in ':asc' or ':desc'".formatted(term));
        };
    }

    /** The comma-separated values of an {@code in} term, each coerced to the field's declared type. */
    private static List<Object> list(SchemaField field, String rawValue) {
        List<Object> values = new ArrayList<>();
        for (String element : rawValue.split(",", -1)) {
            values.add(coerce(field, element));
        }
        return values;
    }

    private static Object value(SchemaField field, String rawValue, String term) {
        return coerce(field, required(rawValue, term));
    }

    private static String required(String rawValue, String term) {
        if (rawValue == null) {
            throw new IllegalArgumentException("where term '%s' needs a value: 'field:op:value'".formatted(term));
        }
        return rawValue;
    }

    /**
     * Reads one text value as the field's declared type.
     *
     * <p>An empty string stays an empty string for the text types — a plugin may legitimately store one,
     * and {@code slug:eq:} asking for it is a real question — while every other type refuses it, since
     * "no number" is not a number.
     */
    private static Object coerce(SchemaField field, String raw) {
        try {
            return switch (field.type()) {
                case STRING, TEXT -> raw;
                case INTEGER -> Long.valueOf(raw.trim());
                case NUMBER -> Double.valueOf(raw.trim());
                case BOOLEAN -> bool(field, raw);
                case TIMESTAMP -> Instant.parse(raw.trim());
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "value '%s' is not a valid %s for field '%s'".formatted(
                            raw, field.type().name().toLowerCase(Locale.ROOT), field.name()));
        }
    }

    /**
     * Strictly {@code true} or {@code false}.
     *
     * <p>{@link Boolean#parseBoolean} reads anything else as {@code false}, so {@code published:eq:yes}
     * would quietly return the unpublished rows — the wrong answer, delivered confidently.
     */
    private static Boolean bool(SchemaField field, String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "false".equals(normalized)) {
            return Boolean.valueOf(normalized);
        }
        throw new IllegalArgumentException(
                "value '%s' is not a valid boolean for field '%s'; use true or false"
                        .formatted(raw, field.name()));
    }
}
