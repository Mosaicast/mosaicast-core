// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Drops expired sessions from whichever store is in use (ARCHITECTURE §8.5). With a 30-day timeout an
 * abandoned session would otherwise sit around for a month, and the in-memory store never removes one
 * nobody asks for again.
 *
 * <p>{@code @SchedulerLock} so two instances sharing the Postgres store don't both delete the same rows; the
 * in-memory store is per instance, where the lock only spaces the sweeps out.
 */
@Component
public class SessionSweep {

    private static final Logger log = LoggerFactory.getLogger(SessionSweep.class);

    private final ObjectProvider<SessionConfig.InMemorySessions> memory;
    private final ObjectProvider<JdbcIndexedSessionRepository> jdbc;

    public SessionSweep(ObjectProvider<SessionConfig.InMemorySessions> memory,
                        ObjectProvider<JdbcIndexedSessionRepository> jdbc) {
        this.memory = memory;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${mosaicast.session.sweep-interval-ms:3600000}", initialDelay = 300_000)
    @SchedulerLock(name = "session-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void sweep() {
        memory.ifAvailable(sessions -> {
            int removed = sessions.removeExpired();
            if (removed > 0) {
                log.info("Session sweep: removed {} expired session(s), {} left", removed, sessions.size());
            }
        });
        jdbc.ifAvailable(JdbcIndexedSessionRepository::cleanUpExpiredSessions);
    }
}
