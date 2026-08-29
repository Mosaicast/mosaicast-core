// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.error;

import java.time.Duration;

/**
 * The site is over the provider's rate limit — 429 with a {@code Retry-After}.
 *
 * <p>Politeness towards somebody else's service, enforced in memory and therefore per instance. That is the
 * same trade {@code FixedWindowRateLimiter} already documents for auth and uploads, and it is defensible here
 * for the same reason: being twice as impolite to a self-hosted LibreTranslate costs nothing. Money would be
 * a different matter, which is why a spend budget does not live in a map.
 */
public class ExternalRateLimitedException extends ExternalServiceException {

    private static final long serialVersionUID = 1L;

    private final Duration retryAfter;

    public ExternalRateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long to wait before trying again. */
    public Duration retryAfter() {
        return retryAfter;
    }

    @Override
    public String problemType() {
        return "external-rate-limited";
    }
}
