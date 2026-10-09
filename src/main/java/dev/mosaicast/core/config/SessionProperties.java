// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a sign-in lasts, and where sessions are kept (ARCHITECTURE §8.5).
 *
 * <p>Set from the environment as {@code MOSAICAST_SESSION_TIMEOUT} (e.g. {@code 30d}, {@code 12h}) and
 * {@code MOSAICAST_SESSION_STORE}. An unknown store fails startup: a typo that quietly fell back to memory
 * would look like a working setting until the first restart logged everyone out.
 *
 * @param timeout how long a session lives without a visit; every visit starts it again. Default 30 days
 * @param store   {@code memory} (default; lost on restart) or {@code jdbc} (Postgres; survives a restart)
 */
@ConfigurationProperties(prefix = "mosaicast.session")
public record SessionProperties(Duration timeout, String store) {

    /** Keep sessions in this process. */
    public static final String MEMORY = "memory";

    /** Keep sessions in Postgres. */
    public static final String JDBC = "jdbc";

    public SessionProperties {
        timeout = timeout == null ? Duration.ofDays(30) : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("mosaicast.session.timeout must be positive; got " + timeout);
        }
        store = store == null || store.isBlank() ? MEMORY : store.trim().toLowerCase(Locale.ROOT);
        if (!Set.of(MEMORY, JDBC).contains(store)) {
            throw new IllegalArgumentException(
                    "mosaicast.session.store must be 'memory' or 'jdbc'; got '" + store + "'");
        }
    }

    /** The timeout in whole days, rounded up and at least one, for the storage disclosure. */
    public int timeoutDays() {
        long days = (timeout.toHours() + 23) / 24;
        return (int) Math.max(1, days);
    }
}
