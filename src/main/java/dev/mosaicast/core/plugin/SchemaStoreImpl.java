// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.SchemaStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The host's {@link SchemaStore} for one plugin (ARCHITECTURE §7.6).
 *
 * <p><strong>The scoping story is that a plugin cannot express the question.</strong> Every method takes a
 * declared entity name and declared field names; this class resolves them against that plugin's own
 * manifest and builds the statement itself. There is no SQL string to sanitize and no table identifier to
 * get wrong — reaching another plugin's tables, or core's, is not blocked so much as unsayable. Values
 * never appear in the statement text; they are bound as JDBC parameters, always.
 *
 * <p>An undeclared entity or field throws {@link IllegalArgumentException}, per the SDK contract: it means
 * the manifest and the code disagree, which is a programming error and fails the same way against the test
 * kit as against the host.
 */
public class SchemaStoreImpl implements SchemaStore {

    private final String pluginId;
    private final Map<String, PluginSchemaValidator.Entity> entities;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SchemaStoreImpl(String pluginId, Map<String, PluginSchemaValidator.Entity> entities,
                           JdbcTemplate jdbc, ObjectMapper mapper) {
        this.pluginId = pluginId;
        this.entities = entities;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String namespace() {
        return PluginSchemaValidator.namespace(pluginId);
    }

    @Override
    public Set<String> entities() {
        return Set.copyOf(entities.keySet());
    }

    @Override
    public <T> Optional<T> find(String entity, long id, Class<T> type) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from %s where id = ?".formatted(table(resolved)), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0), type));
    }

    @Override
    public <T> List<T> select(String entity, Criteria criteria, Class<T> type) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        List<Object> args = new ArrayList<>();
        String sql = "select * from %s%s%s%s".formatted(
                table(resolved),
                where(resolved, criteria, args),
                orderBy(resolved, criteria),
                limitOffset(criteria, args));
        return jdbc.queryForList(sql, args.toArray()).stream().map(row -> map(row, type)).toList();
    }

    @Override
    public <T> List<T> search(String entity, String field, String text, Criteria criteria, Class<T> type) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        SchemaField target = fulltextField(resolved, field, entity);
        if (text == null || text.isEmpty()) {
            // The SDK contract: an empty query matches nothing rather than everything.
            return List.of();
        }

        String column = quote(target.name());
        String match = match(column);

        // Parameters are appended in the order the statement will read them: the match, then the criteria's
        // own predicates, then the ranking term, then limit/offset.
        List<Object> args = new ArrayList<>();
        args.add(text);

        List<Object> criteriaArgs = new ArrayList<>();
        String extra = where(resolved, criteria, criteriaArgs);
        String whereClause = " where " + match
                + (extra.isEmpty() ? "" : " and " + extra.substring(" where ".length()));
        args.addAll(criteriaArgs);

        String ordering;
        if (criteria == null || criteria.orders().isEmpty()) {
            // Best match first, unless the caller ordered it themselves — which the contract says replaces
            // the relevance order rather than refining it.
            ordering = " order by ts_rank(to_tsvector('simple', coalesce(%s, '')), "
                    .formatted(column) + "websearch_to_tsquery('simple', ?)) desc";
            args.add(text);
        } else {
            ordering = orderBy(resolved, criteria);
        }

        String sql = "select * from %s%s%s%s".formatted(
                table(resolved), whereClause, ordering, limitOffset(criteria, args));
        return jdbc.queryForList(sql, args.toArray()).stream().map(row -> map(row, type)).toList();
    }

    /**
     * How many rows one {@link #search} would match — the count the SDK's {@code SchemaStore} has no method
     * for, because a plugin's Java code pages with {@code limit}/{@code offset} and rarely needs a total.
     *
     * <p>The HTTP surface does: it answers in a paginated envelope carrying {@code totalElements}, and a
     * total invented from the page's own length tells a client there is nothing after the page it is
     * holding. Core-only, deliberately — adding it to the plugin contract would be a second way to ask the
     * same question, versioned forever.
     *
     * @param entity   the declared entity
     * @param field    the field declared {@code :fulltext}
     * @param text     the search text; empty matches nothing, as in {@link #search}
     * @param criteria extra predicates, ANDed with the match; ordering and paging are ignored
     * @return the number of matching rows
     */
    public long searchCount(String entity, String field, String text, Criteria criteria) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        SchemaField target = fulltextField(resolved, field, entity);
        if (text == null || text.isEmpty()) {
            return 0;
        }

        List<Object> args = new ArrayList<>();
        args.add(text);
        List<Object> criteriaArgs = new ArrayList<>();
        String extra = where(resolved, criteria, criteriaArgs);
        String whereClause = " where " + match(quote(target.name()))
                + (extra.isEmpty() ? "" : " and " + extra.substring(" where ".length()));
        args.addAll(criteriaArgs);

        Long count = jdbc.queryForObject(
                "select count(*) from %s%s".formatted(table(resolved), whereClause), Long.class,
                args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public long count(String entity, Criteria criteria) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        List<Object> args = new ArrayList<>();
        // Ordering, limit and offset are ignored here, per the contract.
        String sql = "select count(*) from %s%s".formatted(table(resolved), where(resolved, criteria, args));
        Long count = jdbc.queryForObject(sql, Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public long insert(String entity, Map<String, Object> values) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        requireNoId(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("insert needs at least one value for entity '" + entity + "'");
        }
        List<String> columns = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        values.forEach((name, value) -> {
            columns.add(quote(field(resolved, name).name()));
            args.add(bind(value));
        });
        String sql = "insert into %s (%s) values (%s) returning id".formatted(
                table(resolved), String.join(", ", columns),
                columns.stream().map(c -> "?").collect(Collectors.joining(", ")));
        Long id = jdbc.queryForObject(sql, Long.class, args.toArray());
        return id == null ? 0 : id;
    }

    @Override
    public int update(String entity, long id, Map<String, Object> values) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        requireNoId(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("update needs at least one value for entity '" + entity + "'");
        }
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        values.forEach((name, value) -> {
            assignments.add(quote(field(resolved, name).name()) + " = ?");
            args.add(bind(value));
        });
        args.add(id);
        return jdbc.update("update %s set %s where id = ?".formatted(
                table(resolved), String.join(", ", assignments)), args.toArray());
    }

    @Override
    public int delete(String entity, Criteria criteria) {
        PluginSchemaValidator.Entity resolved = entity(entity);
        List<Object> args = new ArrayList<>();
        // Ordering, limit and offset are ignored: a delete is not a paged read. Criteria.all() empties the
        // entity, which the contract allows and calls deliberate.
        return jdbc.update("delete from %s%s".formatted(
                table(resolved), where(resolved, criteria, args)), args.toArray());
    }

    // ---- statement building ----

    /**
     * The full-text predicate for one column.
     *
     * <p>{@code websearch_to_tsquery} takes what a person would type — quotes, OR, a leading minus — and
     * never throws on syntax, unlike {@code to_tsquery}. A plugin passes its users' words straight through,
     * and a parse error from a stray operator would surface as a 500 inside that plugin's UI.
     */
    private static String match(String column) {
        return "to_tsvector('simple', coalesce(%s, '')) @@ websearch_to_tsquery('simple', ?)"
                .formatted(column);
    }

    /** The named field, or a throw — searching a field with no full-text index is a caller error. */
    private static SchemaField fulltextField(PluginSchemaValidator.Entity resolved, String field,
                                             String entity) {
        SchemaField target = field(resolved, field);
        if (!target.fulltext()) {
            throw new IllegalArgumentException(
                    "field '%s' of entity '%s' is not declared :fulltext".formatted(field, entity));
        }
        return target;
    }

    /** The {@code where} clause for a criteria, appending its values to {@code args} in order. */
    private String where(PluginSchemaValidator.Entity entity, Criteria criteria, List<Object> args) {
        if (criteria == null || criteria.predicates().isEmpty()) {
            return "";
        }
        List<String> conditions = new ArrayList<>();
        for (Criteria.Predicate predicate : criteria.predicates()) {
            String column = quote(field(entity, predicate.field()).name());
            switch (predicate.op()) {
                case IS_NULL -> conditions.add(column + " is null");
                case IS_NOT_NULL -> conditions.add(column + " is not null");
                case IN -> {
                    Collection<?> values = (Collection<?>) predicate.value();
                    conditions.add(column + " in (%s)".formatted(
                            values.stream().map(v -> "?").collect(Collectors.joining(", "))));
                    values.forEach(value -> args.add(bind(value)));
                }
                default -> {
                    conditions.add("%s %s ?".formatted(column, sqlOp(predicate.op())));
                    args.add(bind(predicate.value()));
                }
            }
        }
        return " where " + String.join(" and ", conditions);
    }

    private String orderBy(PluginSchemaValidator.Entity entity, Criteria criteria) {
        if (criteria == null || criteria.orders().isEmpty()) {
            return "";
        }
        return " order by " + criteria.orders().stream()
                .map(order -> "%s %s".formatted(
                        quote(field(entity, order.field()).name()),
                        order.direction() == Criteria.Direction.DESC ? "desc" : "asc"))
                .collect(Collectors.joining(", "));
    }

    /** {@code limit}/{@code offset} as bound parameters — they are values, not statement structure. */
    private static String limitOffset(Criteria criteria, List<Object> args) {
        if (criteria == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        if (criteria.limit().isPresent()) {
            sql.append(" limit ?");
            args.add(criteria.limit().getAsInt());
        }
        if (criteria.offset() > 0) {
            sql.append(" offset ?");
            args.add(criteria.offset());
        }
        return sql.toString();
    }

    private static String sqlOp(Criteria.Op op) {
        return switch (op) {
            case EQ -> "=";
            case NE -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case LIKE -> "like";
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    // ---- name resolution: the scoping guarantee ----

    private PluginSchemaValidator.Entity entity(String name) {
        PluginSchemaValidator.Entity resolved = entities.get(name);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Entity '%s' is not declared by plugin '%s'; declared: %s"
                            .formatted(name, pluginId, entities.keySet()));
        }
        return resolved;
    }

    private static SchemaField field(PluginSchemaValidator.Entity entity, String name) {
        return entity.field(name);
    }

    private static void requireNoId(Map<String, Object> values) {
        if (values != null && values.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("id"))) {
            throw new IllegalArgumentException("'id' is assigned by the platform and must not be supplied");
        }
    }

    private static String table(PluginSchemaValidator.Entity entity) {
        return quote(entity.tableName());
    }

    private static String quote(String identifier) {
        return PluginSchemaMigrator.quoted(identifier);
    }

    /** Converts a plugin-supplied value into something JDBC binds; the driver has no {@code Instant}. */
    private static Object bind(Object value) {
        return value instanceof Instant instant ? Timestamp.from(instant) : value;
    }

    /** Maps one row onto the caller's type, by component name (the SDK's convention throughout). */
    private <T> T map(Map<String, Object> row, Class<T> type) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((column, value) -> normalized.put(column, normalize(value)));
        return mapper.convertValue(normalized, type);
    }

    /**
     * Puts a JDBC value into the shape a record component expects.
     *
     * <p>Timestamps come back as {@link Timestamp}, which Jackson would render as a number of milliseconds
     * and hand to an {@link Instant} component as an epoch — right by accident, wrong the moment the
     * component is a {@code String} or the mapper's date handling differs. Converting here means the
     * mapper only ever sees types it maps unambiguously.
     */
    private static Object normalize(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return value;
    }
}
