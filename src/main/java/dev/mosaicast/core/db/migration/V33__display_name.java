// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.db.migration;

import dev.mosaicast.core.auth.DisplayNameProperties;
import dev.mosaicast.core.auth.DisplayNames;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
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
        // Only the taken keys stay in memory, which uniqueness needs. It used to hold every user as well and
        // send one batch for the whole table — a heap risk in a migration that blocks startup (core#201).
        // The rows are streamed through a cursor (a fetch size inside the migration's transaction) and
        // written in bounded batches.
        Set<String> taken = new HashSet<>();
        int maxLength = maxLength();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, display_name FROM app_user ORDER BY created_at ASC, id ASC");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE app_user SET display_name = ?, display_key = ? WHERE id = ?")) {
            select.setFetchSize(BATCH);
            int pending = 0;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    UUID id = (UUID) rows.getObject("id");
                    String cleaned = DisplayNames.clean(rows.getString("display_name"));
                    if (cleaned.isEmpty()) {
                        cleaned = DisplayNames.generatedFor(id);
                    }
                    String name = disambiguate(cleaned, id, taken, maxLength);
                    String key = DisplayNames.canonicalise(name);
                    taken.add(key);
                    update.setString(1, name);
                    update.setString(2, key);
                    update.setObject(3, id);
                    update.addBatch();
                    if (++pending == BATCH) {
                        update.executeBatch();
                        pending = 0;
                    }
                }
            }
            if (pending > 0) {
                update.executeBatch();
            }
        }
    }

    /** Rows per fetch and per update batch. */
    private static final int BATCH = 500;

    /**
     * The longest name the runtime accepts, read the way the application reads it — the bean does not exist
     * yet while migrations run. Names written longer than this were ones the validator then rejected on the
     * first edit (core#201).
     */
    static int maxLength() {
        String configured = System.getenv("MOSAICAST_DISPLAY_NAME_MAX");
        try {
            return configured == null ? DisplayNameProperties.DEFAULT_MAX : Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            return DisplayNameProperties.DEFAULT_MAX;
        }
    }

    /** The first {@code limit} codepoints of a name, whitespace at the cut removed. */
    static String fit(String name, int limit) {
        if (name.codePointCount(0, name.length()) <= limit) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, Math.max(0, limit))).strip();
    }

    /**
     * The first free spelling of a name: as typed, then with four hex digits of the user's own id, then
     * eight, then the whole id.
     *
     * <p>Terminates because the last candidate contains a primary key. Deriving the suffix from the id
     * rather than a counter keeps the result the same whichever order two installs happen to run in, which
     * is what makes this reproducible against a restored backup.
     */
    static String disambiguate(String cleaned, UUID id, Set<String> taken, int maxLength) {
        String plain = id.toString().replace("-", "");
        // Each candidate fits the configured maximum: the suffix used to be appended to a name of any length,
        // so a 32-character name became 37 or 65, and a long name from the login provider was kept whole.
        String[] candidates = {
            fit(cleaned, maxLength),
            fit(cleaned, maxLength - 5) + " " + plain.substring(0, 4),
            fit(cleaned, maxLength - 9) + " " + plain.substring(0, 8),
            plain.substring(0, Math.min(plain.length(), maxLength)),
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
