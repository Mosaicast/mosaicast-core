// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A keyed fixed-window counter — the mechanism behind the basic rate limiting §13 asks for on auth
 * endpoints and uploads.
 *
 * <p><strong>Fixed window, not sliding.</strong> At a window boundary a caller can burst up to twice the
 * limit, which for the job here — making credential stuffing and upload floods expensive — does not matter.
 * A sliding window costs per-request bookkeeping to buy fairness nobody is asking for.
 *
 * <p><strong>The whole decision happens inside one {@code compute}.</strong> {@code PluginLogRateLimiter}
 * shipped as a {@code compute} followed by a separate {@code put}, which is two atomic operations and
 * therefore not one: a thread sitting between them while the window rolled over wrote its stale start time
 * and inflated count back over the fresh window, so the new window was born already exhausted. That was
 * found and fixed in `0.5.18`; this class is built that way from the start rather than rediscovering it.
 * The mapping function stays free of side effects and safe to re-run, so the outcome travels out on the
 * window record instead of being assigned to a local.
 *
 * <p><strong>In-memory, per instance.</strong> §13 puts shared state behind Redis at v3; until then N
 * instances mean N buckets, so the effective limit multiplies by the instance count. The shipped deployment
 * is a single container, and a limiter that is approximate on a configuration nobody runs yet is a better
 * trade than a Redis dependency added early for it.
 */
public class FixedWindowRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** What a caller should do with a request that has just been counted. */
    public record Decision(boolean allowed, Duration retryAfter) {

        /** Within budget; {@code retryAfter} is meaningless and zero. */
        public static Decision pass() {
            return new Decision(true, Duration.ZERO);
        }
    }

    /**
     * Counts one request against a key's budget and says whether it may proceed.
     *
     * @param key    what to count against — a client address, a user id, whatever the caller buckets by
     * @param limit  how many requests one window allows; values below 1 are treated as 1
     * @param window how long a window lasts
     * @param now    the current instant, passed in so tests do not have to sleep
     * @return the decision, carrying how long to wait when refused
     */
    public Decision check(String key, int limit, Duration window, Instant now) {
        int effectiveLimit = Math.max(1, limit);
        Window counted = windows.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt().plus(window))) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.count() + 1);
        });

        if (counted.count() <= effectiveLimit) {
            return Decision.pass();
        }
        Duration remaining = Duration.between(now, counted.startedAt().plus(window));
        // Near the end of a window the remainder is a sliver — a millisecond, or nothing, or negative if the
        // clock moved. "Retry after 0 seconds" is not an answer to give anyone, and a caller that honours it
        // literally comes straight back and is refused again, so the floor is a whole second.
        Duration floor = Duration.ofSeconds(1);
        return new Decision(false, remaining.compareTo(floor) > 0 ? remaining : floor);
    }

    /**
     * Drops windows that have expired.
     *
     * <p>The map is keyed by client address, so without this a long-running instance accumulates one entry
     * per address that ever hit a limited endpoint — small, but unbounded, and unbounded is the part that
     * matters. Called on a schedule rather than per request, so the hot path stays a single {@code compute}.
     *
     * @param window how long a window lasts
     * @param now    the current instant
     */
    public void evictExpired(Duration window, Instant now) {
        windows.values().removeIf(entry -> !now.isBefore(entry.startedAt().plus(window)));
    }

    /** How many keys are currently tracked — for tests and diagnostics. */
    public int tracked() {
        return windows.size();
    }

    private record Window(Instant startedAt, int count) {
    }
}
