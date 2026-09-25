// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.cache;

import dev.mosaicast.core.external.ExternalServiceKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes cached external-service results (ARCHITECTURE §12.7).
 *
 * <p>Plain JDBC rather than the repository, because both hot paths are one statement each and one of them —
 * the read — deliberately does something JPA would fight: it updates the row it just read, but only
 * sometimes.
 */
@Service
public class ExternalCacheStore {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * How stale the hit counter may get before a read bothers to write.
     *
     * <p>A cache read that always updated {@code hits} and {@code last_read_at} would turn every hit into a
     * write, which is how a read path acquires lock contention nobody predicted. Updating once a day keeps
     * LRU eviction meaningful and the admin's numbers honest while making ~99% of hits a no-op.
     */
    private static final Duration ACCOUNTING_INTERVAL = Duration.ofDays(1);

    private final JdbcTemplate jdbc;

    public ExternalCacheStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The cached payload for a key, if there is a live one.
     *
     * <p>Expiry is checked in SQL rather than in Java so a stale row is never served by an instance whose
     * clock has drifted forward relative to the database.
     */
    @Transactional
    public Optional<JsonNode> get(String cacheKey) {
        var rows = jdbc.query(
                "SELECT payload::text FROM external_cache "
                        + "WHERE cache_key = ? AND (expires_at IS NULL OR expires_at > now())",
                (rs, rowNum) -> rs.getString(1), cacheKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        // Accounting, cheaply: the predicate makes this a no-op for a row already counted today.
        jdbc.update("UPDATE external_cache SET hits = hits + 1, last_read_at = now() "
                + "WHERE cache_key = ? AND last_read_at < now() - ?::interval",
                cacheKey, ACCOUNTING_INTERVAL.toDays() + " days");
        return Optional.of(JSON.readTree(rows.getFirst()));
    }

    /** Stores a result, replacing any entry under the same key. */
    @Transactional
    public void put(String cacheKey, ExternalServiceKind kind, String providerId, JsonNode payload,
                    Duration ttl) {
        String json = payload.toString();
        Instant expiresAt = ttl == null || ttl.isZero() || ttl.isNegative() ? null : Instant.now().plus(ttl);
        jdbc.update("INSERT INTO external_cache "
                        + "(cache_key, kind, provider_id, payload, payload_bytes, expires_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?) "
                        + "ON CONFLICT (cache_key) DO UPDATE SET payload = EXCLUDED.payload, "
                        + "payload_bytes = EXCLUDED.payload_bytes, expires_at = EXCLUDED.expires_at, "
                        + "created_at = now(), last_read_at = now()",
                // Bytes, as the column says: `length()` counts UTF-16 units, which understates exactly the
                // payloads this cache holds most — translations of non-ASCII text — and the trim decision is
                // taken from this number (core#201).
                cacheKey, kind.id(), providerId, json, json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
    }

    /**
     * Drops entries for a kind, optionally narrowed to one provider.
     *
     * @return how many rows went
     */
    @Transactional
    public long purge(ExternalServiceKind kind, String providerId) {
        return providerId == null || providerId.isBlank()
                ? jdbc.update("DELETE FROM external_cache WHERE kind = ?", kind.id())
                : jdbc.update("DELETE FROM external_cache WHERE kind = ? AND provider_id = ?",
                        kind.id(), providerId);
    }

    /** What the admin cache panel shows. */
    @Transactional(readOnly = true)
    public Stats stats(ExternalServiceKind kind) {
        return jdbc.queryForObject(
                "SELECT count(*), coalesce(sum(hits), 0), coalesce(sum(payload_bytes), 0), min(created_at) "
                        + "FROM external_cache WHERE kind = ?",
                (rs, rowNum) -> new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                kind.id());
    }

    /**
     * @param entries     how many results are held
     * @param hits        how many calls they have saved
     * @param bytes       roughly how much space they take
     * @param oldestEntry when the earliest was stored, or {@code null} when empty
     */
    public record Stats(long entries, long hits, long bytes, Instant oldestEntry) {
    }

    /** Deletes expired rows. Returns how many. */
    @Transactional
    public int deleteExpired() {
        return jdbc.update("DELETE FROM external_cache WHERE expires_at IS NOT NULL AND expires_at <= now()");
    }

    /** Trims the table to its bound, dropping least-recently-read rows first. Returns how many went. */
    @Transactional
    public int trimTo(long maxEntries) {
        return jdbc.update("DELETE FROM external_cache WHERE cache_key IN ("
                + "SELECT cache_key FROM external_cache ORDER BY last_read_at DESC OFFSET ?)", maxEntries);
    }
}
