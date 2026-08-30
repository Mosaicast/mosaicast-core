// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.external.ExternalServiceKind;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The cache's storage half (ARCHITECTURE §12.7) — the SQL the pipeline test deliberately does not touch. */
@SpringBootTest
@Testcontainers
class ExternalCacheIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ExternalCacheStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private static tools.jackson.databind.JsonNode payload(String text) {
        return JsonMapper.builder().build().readTree("{\"text\":\"%s\"}".formatted(text));
    }

    @Test
    void storesAndReturnsAResult() {
        store.put("k-store", ExternalServiceKind.TRANSLATION, "stub", payload("hallo"), Duration.ofDays(1));

        assertThat(store.get("k-store")).isPresent();
        assertThat(store.get("k-store").orElseThrow().get("text").stringValue()).isEqualTo("hallo");
        assertThat(store.get("missing-entirely")).isEmpty();
    }

    @Test
    void anExpiredEntryIsNotServedEvenBeforeTheSweeperRuns() {
        // Expiry is a SQL predicate, not a Java comparison, so an instance whose clock drifted forward
        // cannot serve something the database considers dead.
        store.put("k-expired", ExternalServiceKind.TRANSLATION, "stub", payload("stale"),
                Duration.ofMillis(1));
        jdbc.update("UPDATE external_cache SET expires_at = now() - interval '1 hour' WHERE cache_key = ?",
                "k-expired");

        assertThat(store.get("k-expired")).isEmpty();
        assertThat(store.deleteExpired()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aNullTtlMeansItStaysUntilSomethingPurgesIt() {
        store.put("k-forever", ExternalServiceKind.TRANSLATION, "stub", payload("kept"), null);

        assertThat(jdbc.queryForObject(
                "SELECT expires_at FROM external_cache WHERE cache_key = ?",
                java.sql.Timestamp.class, "k-forever")).isNull();
        assertThat(store.get("k-forever")).isPresent();
    }

    @Test
    void hitAccountingDoesNotWriteOnEveryRead() {
        // A cache read that always wrote would turn the read path into a write path — which is how lock
        // contention arrives somewhere nobody was looking for it.
        store.put("k-hits", ExternalServiceKind.TRANSLATION, "stub", payload("x"), null);
        for (int i = 0; i < 5; i++) {
            store.get("k-hits");
        }

        assertThat(jdbc.queryForObject("SELECT hits FROM external_cache WHERE cache_key = ?",
                Long.class, "k-hits")).isZero();

        // Once the row is a day stale, the next read counts it.
        jdbc.update("UPDATE external_cache SET last_read_at = now() - interval '2 days' WHERE cache_key = ?",
                "k-hits");
        store.get("k-hits");

        assertThat(jdbc.queryForObject("SELECT hits FROM external_cache WHERE cache_key = ?",
                Long.class, "k-hits")).isEqualTo(1);
    }

    @Test
    void oneProvidersEntriesCanBePurgedAlone() {
        store.put("k-a", ExternalServiceKind.TRANSLATION, "alpha", payload("a"), null);
        store.put("k-b", ExternalServiceKind.TRANSLATION, "beta", payload("b"), null);

        assertThat(store.purge(ExternalServiceKind.TRANSLATION, "alpha")).isEqualTo(1);
        assertThat(store.get("k-a")).isEmpty();
        assertThat(store.get("k-b")).as("a different provider is untouched").isPresent();
    }

    @Test
    void reportsWhatItIsHolding() {
        store.purge(ExternalServiceKind.TRANSLATION, null);
        store.put("k-s1", ExternalServiceKind.TRANSLATION, "stub", payload("one"), null);
        store.put("k-s2", ExternalServiceKind.TRANSLATION, "stub", payload("two"), null);

        ExternalCacheStore.Stats stats = store.stats(ExternalServiceKind.TRANSLATION);

        assertThat(stats.entries()).isEqualTo(2);
        assertThat(stats.bytes()).isPositive();
        assertThat(stats.oldestEntry()).isNotNull();
    }

    @Test
    void trimmingDropsTheLeastRecentlyReadFirst() {
        store.purge(ExternalServiceKind.TRANSLATION, null);
        store.put("k-old", ExternalServiceKind.TRANSLATION, "stub", payload("old"), null);
        store.put("k-new", ExternalServiceKind.TRANSLATION, "stub", payload("new"), null);
        jdbc.update("UPDATE external_cache SET last_read_at = now() - interval '9 days' "
                + "WHERE cache_key = ?", "k-old");

        assertThat(store.trimTo(1)).isEqualTo(1);
        assertThat(store.get("k-new")).as("the recently read survives").isPresent();
        assertThat(store.get("k-old")).isEmpty();
    }

    @Test
    void reWritingAKeyReplacesTheEntryRatherThanFailing() {
        store.put("k-dup", ExternalServiceKind.TRANSLATION, "stub", payload("first"), null);
        store.put("k-dup", ExternalServiceKind.TRANSLATION, "stub", payload("second"), null);

        assertThat(store.get("k-dup").orElseThrow().get("text").stringValue()).isEqualTo("second");
    }
}
