// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * When the edge last polled the policy, and which build it said it was (ARCHITECTURE §18.2).
 *
 * <p>In memory, not the database: it is a liveness signal for the admin page, not history, and an instance
 * that restarts has, correctly, not heard from the edge yet. Recorded only for a poll carrying the shared
 * token, so "edge connected" cannot be produced by anyone able to send a header — the claim the admin page
 * makes from it is "your settings are being enforced", and that one must not be forgeable.
 */
@Component
public class EdgeHeartbeat {

    /** A poll older than this reads as stale; the edge polls every few seconds. */
    static final Duration FRESH = Duration.ofSeconds(60);

    /** Longest build label kept; it is shown verbatim on the admin page. */
    static final int BUILD_MAX = 64;

    /** What the admin page says about the edge. */
    public enum Status { CONNECTED, STALE, UNKNOWN }

    /** The last report, if any. */
    public record Report(Status status, @Nullable Instant lastSeen, @Nullable String build) {
    }

    private record Seen(Instant at, @Nullable String build) {
    }

    private final EdgeProperties properties;
    private final Clock clock;
    private final AtomicReference<@Nullable Seen> last = new AtomicReference<>();

    public EdgeHeartbeat(EdgeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Records a poll if {@code token} is the configured one; anything else is ignored silently. */
    public void record(@Nullable String token, @Nullable String build) {
        if (!properties.hasToken() || token == null) {
            return;
        }
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        last.set(new Seen(clock.instant(), sanitize(build)));
    }

    /** The edge as the admin page should describe it. */
    public Report report() {
        if (!properties.hasToken()) {
            return new Report(Status.UNKNOWN, null, null);
        }
        Seen seen = last.get();
        if (seen == null) {
            return new Report(Status.STALE, null, null);
        }
        boolean fresh = Duration.between(seen.at(), clock.instant()).compareTo(FRESH) < 0;
        return new Report(fresh ? Status.CONNECTED : Status.STALE, seen.at(), seen.build());
    }

    /** Printable ASCII only and bounded: the label is echoed onto an admin page. */
    private static @Nullable String sanitize(@Nullable String build) {
        if (build == null) {
            return null;
        }
        String cleaned = build.strip().replaceAll("[^\\x21-\\x7e]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > BUILD_MAX ? cleaned.substring(0, BUILD_MAX) : cleaned;
    }
}
