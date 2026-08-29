// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.pipeline;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.error.ExternalRateLimitedException;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps this site polite towards somebody else's service (ARCHITECTURE §12.7).
 *
 * <p>Reuses {@link FixedWindowRateLimiter} unchanged, and inherits its honest limitation: in memory, per
 * instance, so N instances multiply the effective limit. That is the same trade already accepted for auth
 * and uploads, and it is defensible here because being twice as impolite to a self-hosted LibreTranslate
 * costs nothing. Money would be different, which is why a spend budget does not belong in a map.
 */
public final class RateLimitedProvider<I, O> implements ExternalProvider<I, O> {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ExternalProvider<I, O> delegate;
    private final FixedWindowRateLimiter limiter;
    private final String key;
    private final int requestsPerMinute;

    public RateLimitedProvider(ExternalProvider<I, O> delegate, FixedWindowRateLimiter limiter,
                               String key, int requestsPerMinute) {
        this.delegate = delegate;
        this.limiter = limiter;
        this.key = key;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public O call(I input, ProviderConfig config) {
        FixedWindowRateLimiter.Decision decision =
                limiter.check(key, requestsPerMinute, WINDOW, Instant.now());
        if (!decision.allowed()) {
            throw new ExternalRateLimitedException(
                    "This site is calling %s too often; try again shortly".formatted(descriptor().name()),
                    decision.retryAfter());
        }
        return delegate.call(input, config);
    }

    @Override
    public ProbeResult probe(ProviderConfig config) {
        // A probe is a real request to the upstream, so it counts. An admin leaning on the Test button
        // should reach this site's limit rather than the provider's.
        FixedWindowRateLimiter.Decision decision =
                limiter.check(key, requestsPerMinute, WINDOW, Instant.now());
        if (!decision.allowed()) {
            return ProbeResult.failed("Too many test calls; try again in %d seconds"
                    .formatted(decision.retryAfter().toSeconds()));
        }
        return delegate.probe(config);
    }
}
