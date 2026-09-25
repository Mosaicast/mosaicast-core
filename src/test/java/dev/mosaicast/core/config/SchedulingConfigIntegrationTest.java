// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The scheduler lock against a real database (core#191).
 *
 * <p>Every scheduled job — feed polls, the external-cache sweep, plugin ticks — relies on this provider to
 * run on exactly one instance. It had no test: a missing {@code shedlock} table, or a provider that
 * silently granted every request, would only have shown up as double polls in a multi-instance deployment.
 */
@SpringBootTest
@Testcontainers
class SchedulingConfigIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LockProvider locks;

    @Test
    void aHeldLockIsNotGrantedTwiceAndIsGrantedAgainOnceReleased() {
        LockConfiguration config = new LockConfiguration(
                Instant.now(), "core191-test-lock", Duration.ofMinutes(1), Duration.ZERO);

        Optional<SimpleLock> first = locks.lock(config);
        assertThat(first).as("the first holder gets the lock").isPresent();
        // What a second instance sees while the first is still running the job.
        assertThat(locks.lock(config)).as("a second holder is refused while it is held").isEmpty();

        first.orElseThrow().unlock();
        Optional<SimpleLock> again = locks.lock(config);
        assertThat(again).as("released means available").isPresent();
        again.orElseThrow().unlock();
    }
}
