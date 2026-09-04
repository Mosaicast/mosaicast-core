// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.db.migration;

import dev.mosaicast.core.auth.DisplayNames;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Editable display names (ARCHITECTURE §8.6): the canonical {@code display_key} uniqueness is enforced on,
 * the rename lock, and the name history a revert walks back through (§8.6.1).
 *
 * <p><strong>Why this is Java and not SQL.</strong> The key has to be computed by
 * {@link DisplayNames#canonicalise}, the same function the runtime validator uses. A SQL approximation —
 * {@code normalize()}, {@code lower()}, {@code translate()} — gets close, and close is the failure mode
 * that matters here: the two would disagree on some input nobody thought about, and the symptom is a unique
 * index rejecting a name the validator just accepted, intermittently, only on unicode, in production. One
 * implementation or none. {@code spring.flyway.locations} names this package for that reason.
 *
 * <p><strong>Collisions are expected, not exceptional.</strong> Every existing name was prefilled from a
 * provider that never promised uniqueness, so two accounts called {@code alex} are a normal state of the
 * database this migration runs against. The oldest account keeps the plain name and later ones are
 * disambiguated with a slice of their own id — a rename nobody asked for, but the alternative is a
 * migration that fails on a live install and leaves the operator to resolve it by hand.
 */
public class V33__display_name extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumns(connection);
        backfillKeys(connection);
        addConstraints(connection);
    }

    private void addColumns(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE app_user ADD COLUMN display_key TEXT");
            statement.execute("ALTER TABLE app_user ADD COLUMN rename_locked_until TIMESTAMPTZ");
            statement.execute("""
                    CREATE TABLE user_name_history (
                        id      UUID        PRIMARY KEY,
                        user_id UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                        name    TEXT        NOT NULL,
                        set_by  TEXT        NOT NULL,
                        set_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            // A revert reads the newest entries for one user and nothing else ever queries this table.
            statement.execute(
                    "CREATE INDEX ix_user_name_history_user ON user_name_history(user_id, set_at DESC)");
        }
    }

    /**
     * Computes a key for every existing user, oldest first so that seniority decides who keeps a contested
     * name — the alternative orderings all amount to renaming whoever the query happened to return second.
     */
    private void backfillKeys(Connection connection) throws Exception {
        Map<UUID, String[]> resolved = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, display_name FROM app_user ORDER BY created_at ASC, id ASC");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                UUID id = (UUID) rows.getObject("id");
                String cleaned = DisplayNames.clean(rows.getString("display_name"));
                if (cleaned.isEmpty()) {
                    cleaned = DisplayNames.generatedFor(id);
                }
                String name = disambiguate(cleaned, id, taken);
                taken.add(DisplayNames.canonicalise(name));
                resolved.put(id, new String[] {name, DisplayNames.canonicalise(name)});
            }
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE app_user SET display_name = ?, display_key = ? WHERE id = ?")) {
            for (Map.Entry<UUID, String[]> entry : resolved.entrySet()) {
                update.setString(1, entry.getValue()[0]);
                update.setString(2, entry.getValue()[1]);
                update.setObject(3, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    /**
     * The first free spelling of a name: as typed, then with four hex digits of the user's own id, then
     * eight, then the whole id.
     *
     * <p>Terminates because the last candidate contains a primary key. Deriving the suffix from the id
     * rather than a counter keeps the result the same whichever order two installs happen to run in, which
     * is what makes this reproducible against a restored backup.
     */
    private static String disambiguate(String cleaned, UUID id, Set<String> taken) {
        String plain = id.toString().replace("-", "");
        String[] candidates = {
            cleaned,
            cleaned + " " + plain.substring(0, 4),
            cleaned + " " + plain.substring(0, 8),
            cleaned + " " + plain,
        };
        for (String candidate : candidates) {
            if (!taken.contains(DisplayNames.canonicalise(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free display name for user " + id);
    }

    private void addConstraints(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE app_user ALTER COLUMN display_key SET NOT NULL");
            statement.execute("CREATE UNIQUE INDEX ux_app_user_display_key ON app_user(display_key)");
        }
    }
}
