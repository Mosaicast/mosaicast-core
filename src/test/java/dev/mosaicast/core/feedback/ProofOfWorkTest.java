// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The anonymous sender's proof of work (§20.4): what passes once, and every way it fails. */
class ProofOfWorkTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final ProofOfWork pow = new ProofOfWork(properties(8), clock);

    @Test
    void aSolvedChallengePassesOnce() {
        ProofOfWork.Challenge challenge = pow.issue();
        assertThat(challenge.bits()).isEqualTo(8);
        String counter = solve(challenge);
        clock.advance(Duration.ofSeconds(5));
        assertThat(pow.verify(challenge.challenge(), counter)).isNull();
        assertThat(pow.verify(challenge.challenge(), counter)).isEqualTo(ProofOfWork.Failure.SPENT);
    }

    @Test
    void aWrongCounterFails() {
        ProofOfWork.Challenge challenge = pow.issue();
        clock.advance(Duration.ofSeconds(5));
        String wrong = "0";
        while (ProofOfWork.meets(challenge.challenge() + ":" + wrong, 8)) {
            wrong = wrong + "1";
        }
        assertThat(pow.verify(challenge.challenge(), wrong)).isEqualTo(ProofOfWork.Failure.WRONG);
    }

    @Test
    void tooFastAndTooLateFail() {
        ProofOfWork.Challenge challenge = pow.issue();
        String counter = solve(challenge);
        assertThat(pow.verify(challenge.challenge(), counter)).isEqualTo(ProofOfWork.Failure.TOO_FAST);
        clock.advance(Duration.ofMinutes(11));
        assertThat(pow.verify(challenge.challenge(), counter)).isEqualTo(ProofOfWork.Failure.EXPIRED);
    }

    @Test
    void aTamperedOrForeignChallengeFails() {
        ProofOfWork.Challenge challenge = pow.issue();
        clock.advance(Duration.ofSeconds(5));
        String tampered = "x" + challenge.challenge().substring(1);
        assertThat(pow.verify(tampered, "1")).isEqualTo(ProofOfWork.Failure.FORGED);
        // Another instance's secret: a restart invalidates challenges in flight.
        ProofOfWork other = new ProofOfWork(properties(8), clock);
        assertThat(other.verify(challenge.challenge(), solve(challenge))).isEqualTo(ProofOfWork.Failure.FORGED);
        assertThat(pow.verify(null, "1")).isEqualTo(ProofOfWork.Failure.MALFORMED);
        assertThat(pow.verify(challenge.challenge(), "12a")).isEqualTo(ProofOfWork.Failure.MALFORMED);
    }

    @Test
    void zeroBitsAreCountedFromTheFrontOfTheHash() {
        // "abc" hashes to ba78…: no leading zero bits at all.
        assertThat(ProofOfWork.meets("abc", 0)).isTrue();
        assertThat(ProofOfWork.meets("abc", 1)).isFalse();
    }

    static String solve(ProofOfWork.Challenge challenge) {
        for (long i = 0; ; i++) {
            if (ProofOfWork.meets(challenge.challenge() + ":" + i, challenge.bits())) {
                return Long.toString(i);
            }
        }
    }

    static FeedbackProperties properties(int bits) {
        return new FeedbackProperties(bits, Duration.ofMinutes(10), Duration.ofSeconds(3), 5, 10, Duration.ofHours(1),
                200, 60, 2000, 200, 2, Duration.ofDays(1), Duration.ofDays(90), 86_400_000L);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
