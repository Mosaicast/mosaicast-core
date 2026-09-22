// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code V33} backfill against the database it will actually meet (ARCHITECTURE §8.6).
 *
 * <p>Every existing display name was prefilled from a provider that never promised uniqueness, so an
 * install with two accounts called {@code alex} is the normal case and not an edge one — and the migration
 * adds a unique index over exactly that column. This runs the schema to {@code V32}, seeds the collisions a
 * real install would have (identical names, differing only in case, in a Cyrillic lookalike, and in
 * invisible padding), and then applies {@code V33}.
 *
 * <p>It is a separate test from {@link DisplayNamesTest} because that one asserts the function and this one
 * asserts the migration survives the data: the failure being guarded against is a live upgrade aborting
 * halfway and leaving an operator to resolve duplicate names by hand.
 */
@Testcontainers
class DisplayNameBackfillMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final List<String> COLLIDING_NAMES = List.of(
            "alex",          // the oldest account keeps this one
            "Alex",          // differs only in case
            "аlex",          // Cyrillic а
            " alex ",        // padding
            "ａｌｅｘ",         // fullwidth
            "Someone Else");

    @Test
    void collidingProviderNamesAreDisambiguatedAndTheOldestKeepsTheName() throws Exception {
        try (Connection connection = connect()) {
            migrateTo(connection, "32");
            seed(connection);
            migrateTo(connection, "33");

            Map<String, String> namesByKey = new LinkedHashMap<>();
            List<String> orderedNames = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT display_name, display_key FROM app_user ORDER BY created_at ASC")) {
                while (rows.next()) {
                    namesByKey.put(rows.getString("display_key"), rows.getString("display_name"));
                    orderedNames.add(rows.getString("display_name"));
                }
            }

            // Every row got a key, and the index that now exists could be created — which is the migration's
            // whole job. The seeded names fold to two distinct people, so five of the six were renamed.
            assertThat(namesByKey).hasSize(COLLIDING_NAMES.size());
            assertThat(orderedNames).hasSize(COLLIDING_NAMES.size());
            assertThat(orderedNames.get(0)).isEqualTo("alex");
            assertThat(orderedNames.get(5)).isEqualTo("Someone Else");
            assertThat(orderedNames.subList(1, 5)).allSatisfy(name ->
                    assertThat(DisplayNames.canonicalise(name)).isNotEqualTo("alex"));

            assertThat(uniqueIndexExists(connection)).isTrue();
        }
    }

    @Test
    void aNameOfNothingButInvisibleCharactersBecomesAGeneratedOne() throws Exception {
        try (Connection connection = connect()) {
            migrateTo(connection, "32");
            UUID id = UUID.fromString("4f2a1b3c-1111-2222-3333-444455556666");
            insertUser(connection, id, "​‌", 0);
            migrateTo(connection, "33");

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT display_name, display_key FROM app_user WHERE id = ?")) {
                select.setObject(1, id);
                try (ResultSet rows = select.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    // Not blank, not the invisible original, and derived from the id so it is stable.
                    assertThat(rows.getString("display_name")).isEqualTo("Listener 4f2a");
                    assertThat(rows.getString("display_key")).isEqualTo("listener 4f2a");
                }
            }
        }
    }

    /**
     * Drops everything before each test.
     *
     * <p>The container is shared, so without this the second test would seed its row into a schema the
     * first had already migrated past — and be rejected by the very constraint it is trying to exercise.
     * A migration test that cannot start from before the migration is testing nothing.
     */
    @BeforeEach
    void freshDatabase() {
        flyway("latest").clean();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs migrations up to and including {@code target}, so V33 can be applied to a seeded V32 schema. */
    private static void migrateTo(Connection connection, String target) {
        flyway(target).migrate();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:dev/mosaicast/core/db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static void seed(Connection connection) throws Exception {
        for (int i = 0; i < COLLIDING_NAMES.size(); i++) {
            insertUser(connection, UUID.randomUUID(), COLLIDING_NAMES.get(i), i);
        }
    }

    /** {@code createdOffset} orders the rows, because seniority decides who keeps a contested name. */
    private static void insertUser(Connection connection, UUID id, String name, int createdOffset)
            throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO app_user (id, display_name, role, created_at)
                VALUES (?, ?, 'FAN', now() + (? || ' seconds')::interval)""")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            insert.setString(3, String.valueOf(createdOffset));
            insert.executeUpdate();
        }
    }

    private static boolean uniqueIndexExists(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM pg_indexes WHERE indexname = 'ux_app_user_display_key'")) {
            return rows.next();
        }
    }
}
