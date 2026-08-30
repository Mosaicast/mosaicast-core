// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.pipeline;

import dev.mosaicast.core.external.ExternalKindSupport;
import dev.mosaicast.core.external.ExternalProperties;
import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.cache.ExternalCacheStore;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Wraps a raw provider in everything that is true of <em>every</em> provider (ARCHITECTURE §12.7).
 *
 * <p>The order is the design, and it is worth stating outright:
 *
 * <pre>
 *   cache            outermost — a hit costs no permit and no token, which is the point of caching
 *     rate limit     politeness towards the upstream
 *       bulkhead     how many of ours may run at once, plus a wall-clock ceiling
 *         provider   shape the request, speak HTTP, shape the response
 * </pre>
 *
 * <p>A provider therefore never implements any of it, which is what keeps a provider small enough to be
 * reviewed by reading it, and what makes "did this provider remember to cache?" a question nobody has to ask.
 */
@Component
public class ExternalCallPipeline {

    private final ExternalCacheStore cache;
    private final ExternalProperties properties;
    private final Map<ExternalServiceKind, ExternalKindSupport<?, ?>> supports = new ConcurrentHashMap<>();

    /** One limiter for every provider, keyed per kind and provider. */
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    /** One bulkhead per kind — only one provider is active at a time, so per-kind is the honest bound. */
    private final Map<ExternalServiceKind, Semaphore> bulkheads = new ConcurrentHashMap<>();

    /**
     * Virtual threads, following {@code PluginExtensions}: the semaphore is the bound, and a thread parked
     * on a socket should not also cost a platform thread.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExternalCallPipeline(ExternalCacheStore cache, ExternalProperties properties,
                                List<ExternalKindSupport<?, ?>> kindSupports) {
        this.cache = cache;
        this.properties = properties;
        kindSupports.forEach(support -> supports.put(support.kind(), support));
    }

    /**
     * The provider a caller should actually invoke.
     *
     * @param raw    the provider bean
     * @param config its resolved settings — the cache key needs their fingerprint
     */
    @SuppressWarnings("unchecked")
    public <I, O> ExternalProvider<I, O> wrap(ExternalServiceKind kind, ExternalProvider<I, O> raw,
                                              ProviderConfig config) {
        ExternalProvider<I, O> wrapped = new BoundedCallProvider<>(raw,
                bulkheads.computeIfAbsent(kind, ignored -> new Semaphore(properties.maxConcurrentOrDefault())),
                executor, properties.maxQueueWaitOrDefault(), properties.callTimeoutOrDefault());

        int rpm = requestsPerMinute(raw, config);
        wrapped = new RateLimitedProvider<>(wrapped, limiter, kind.id() + ':' + raw.descriptor().id(), rpm);

        ExternalKindSupport<I, O> support = (ExternalKindSupport<I, O>) supports.get(kind);
        if (support != null && raw.descriptor().cacheable()) {
            wrapped = new CachingExternalProvider<>(wrapped, cache, support, config);
        }
        return wrapped;
    }

    /**
     * The provider's declared default, unless it also offers a {@code requestsPerMinute} setting the admin
     * has moved — an operator on a paid tier should not be held to a free tier's politeness.
     */
    private static int requestsPerMinute(ExternalProvider<?, ?> raw, ProviderConfig config) {
        if (raw.descriptor().settings().field("requestsPerMinute").isPresent()) {
            try {
                return config.integer("requestsPerMinute");
            } catch (RuntimeException unset) {
                return raw.descriptor().defaultRequestsPerMinute();
            }
        }
        return raw.descriptor().defaultRequestsPerMinute();
    }

    /** Drops rate-limit windows that have expired; called by the same sweep that prunes the cache. */
    public void evictExpiredWindows() {
        limiter.evictExpired(java.time.Duration.ofMinutes(1), java.time.Instant.now());
    }
}
