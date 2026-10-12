// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What the admin page may claim about the edge (ARCHITECTURE §18.2): nothing without a token, "connected"
 * only for a recent poll carrying it, and a build label that cannot smuggle markup or length onto the page.
 */
class EdgeHeartbeatTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-18T10:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    void withoutATokenTheEdgeIsUnknownWhateverPolls() {
        EdgeHeartbeat heartbeat = new EdgeHeartbeat(new EdgeProperties(""), clock);
        heartbeat.record("", "build");
        assertThat(heartbeat.report().status()).isEqualTo(EdgeHeartbeat.Status.UNKNOWN);
    }

    @Test
    void aWrongTokenIsIgnoredAndTheRightOneConnectsUntilItGoesStale() {
        EdgeHeartbeat heartbeat = new EdgeHeartbeat(new EdgeProperties("s3cret"), clock);
        heartbeat.record("guess", "x");
        assertThat(heartbeat.report().status()).isEqualTo(EdgeHeartbeat.Status.STALE);
        assertThat(heartbeat.report().lastSeen()).isNull();

        heartbeat.record("s3cret", "abc123");
        assertThat(heartbeat.report().status()).isEqualTo(EdgeHeartbeat.Status.CONNECTED);
        assertThat(heartbeat.report().build()).isEqualTo("abc123");

        now.set(now.get().plus(EdgeHeartbeat.FRESH));
        assertThat(heartbeat.report().status()).isEqualTo(EdgeHeartbeat.Status.STALE);
    }

    @Test
    void theBuildLabelIsPrintableAndBounded() {
        EdgeHeartbeat heartbeat = new EdgeHeartbeat(new EdgeProperties("s3cret"), clock);
        heartbeat.record("s3cret", " v1 2\n<b>" + "x".repeat(100));
        String build = heartbeat.report().build();
        assertThat(build).doesNotContain(" ", "\n").hasSize(EdgeHeartbeat.BUILD_MAX).startsWith("v12<b>");
    }
}
