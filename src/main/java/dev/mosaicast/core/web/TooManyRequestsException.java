// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.time.Duration;

/**
 * A caller exceeded a rate limit; rendered as RFC 7807 with status 429 by {@link ApiExceptionHandler}.
 *
 * <p>With a {@link #retryAfter()} the answer also carries a {@code Retry-After} header and a top-level
 * {@code retryAfterMs} member, which is what a client backs off on (§7.9). Without one it says only "too
 * many", as it always has.
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message) {
        this(message, null);
    }

    /** @param retryAfter how long until the caller may try again, or null when the limiter cannot say */
    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long until the caller may try again, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
