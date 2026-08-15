// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The counting rules behind §13's basic rate limiting. The clock is a parameter, so none of this sleeps.
 */
class FixedWindowRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Instant T0 = Instant.parse("2026-08-15T12:00:00Z");

    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    @Test
    void requestsUpToTheLimitPassAndTheNextOneDoesNot() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check("a", 3, WINDOW, T0).allowed()).isTrue();
        }
        assertThat(limiter.check("a", 3, WINDOW, T0).allowed()).isFalse();
    }

    @Test
    void refusalSaysHowLongToWait() {
        limiter.check("a", 1, WINDOW, T0);
        FixedWindowRateLimiter.Decision refused = limiter.check("a", 1, WINDOW, T0.plusSeconds(20));

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void aRetryAfterIsNeverZero() {
        // Right at the boundary the arithmetic rounds to nothing; "try again in 0 seconds" is not an answer.
        limiter.check("a", 1, WINDOW, T0);
        FixedWindowRateLimiter.Decision refused = limiter.check("a", 1, WINDOW, T0.plusMillis(59_999));

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aNewWindowStartsCleanRatherThanInheritingTheOldCount() {
        limiter.check("a", 1, WINDOW, T0);
        assertThat(limiter.check("a", 1, WINDOW, T0).allowed()).isFalse();

        // This is the bug PluginLogRateLimiter shipped with: a stale count written over a fresh window left
        // the new window born exhausted, silencing the caller for an extra one.
        assertThat(limiter.check("a", 1, WINDOW, T0.plus(WINDOW)).allowed()).isTrue();
    }

    @Test
    void keysAreCountedSeparately() {
        assertThat(limiter.check("a", 1, WINDOW, T0).allowed()).isTrue();
        assertThat(limiter.check("b", 1, WINDOW, T0).allowed()).isTrue();
        assertThat(limiter.check("a", 1, WINDOW, T0).allowed()).isFalse();
    }

    @Test
    void aLimitBelowOneStillAllowsOneRequest() {
        assertThat(limiter.check("a", 0, WINDOW, T0).allowed()).isTrue();
        assertThat(limiter.check("a", 0, WINDOW, T0).allowed()).isFalse();
    }

    @Test
    void concurrentCallersAreCountedExactlyOnceEach() throws Exception {
        int threads = 32;
        int limit = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    if (limiter.check("shared", limit, WINDOW, T0).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Exactly the limit — no double-counting, and no request slipping through a read-then-write gap.
        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void expiredWindowsAreEvictedSoTheMapDoesNotGrowForever() {
        limiter.check("a", 1, WINDOW, T0);
        limiter.check("b", 1, WINDOW, T0.plusSeconds(50));
        assertThat(limiter.tracked()).isEqualTo(2);

        // At T0+60s "a" has expired and "b" has not.
        limiter.evictExpired(WINDOW, T0.plus(WINDOW));
        assertThat(limiter.tracked()).isEqualTo(1);

        limiter.evictExpired(WINDOW, T0.plusSeconds(200));
        assertThat(limiter.tracked()).isZero();
    }
}
