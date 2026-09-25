// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Retention by row count, with the id gaps a real sequence has (core#201).
 *
 * <p>Capture is set to {@code ERROR} so the application's own logging stays out of the table while this
 * counts rows in it.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "mosaicast.log.capture-level=ERROR")
class AppLogRetentionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private AppLogRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private void insert(int count) {
        for (int i = 0; i < count; i++) {
            repository.save(new AppLogEntry(Instant.now(), AppLogLevel.WARN, "test-retention", "test", null,
                    "row " + i, null, null));
        }
    }

    @Test
    void keepsTheNewestRowsEvenWhenTheIdsHaveGaps() {
        tx.executeWithoutResult(status -> jdbc.update("delete from app_log"));
        insert(5);
        // What a failed insert or a cached sequence block leaves behind.
        jdbc.queryForObject("select setval(pg_get_serial_sequence('app_log', 'id'), "
                + "(select max(id) from app_log) + 1000)", Long.class);
        insert(5);

        int removed = tx.execute(status -> repository.trimToMaxRows(8));

        // Ten rows, a cap of eight: two go. `max(id) - 8` fell inside the gap and took all five older rows.
        assertThat(removed).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(8);
    }

    @Test
    void leavesATableUnderItsLimitAlone() {
        tx.executeWithoutResult(status -> jdbc.update("delete from app_log"));
        insert(3);

        int removed = tx.execute(status -> repository.trimToMaxRows(8));
        assertThat(removed).isZero();
        assertThat(repository.count()).isEqualTo(3);
    }
}
