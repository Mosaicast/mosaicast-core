// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.pipeline;

import dev.mosaicast.core.external.ExternalKindSupport;
import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.cache.ExternalCacheKey;
import dev.mosaicast.core.external.cache.ExternalCacheStore;
import dev.mosaicast.core.external.settings.ProviderConfig;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves a previously-paid-for result instead of calling the provider (ARCHITECTURE §12.7).
 *
 * <p><strong>A decorator, not a base class providers extend.</strong> Inheritance would put the cache in the
 * provider's own type hierarchy — the provider level wearing a kind-level name — and would let a provider
 * lose caching silently by forgetting to extend it, where the failure mode is a bill. It would also drag a
 * repository, and therefore Postgres, into unit tests that only want to assert a request's JSON shape, and
 * burn the one inheritance slot three REST providers will want for shared send/parse code. Decisively: the
 * cache is one of several cross-cutting concerns, and as inheritance they compose into one god base class
 * with flags rather than into an order that can be reasoned about.
 *
 * <p><strong>Outermost in that order</strong>, so a hit costs no queue permit and no rate-limit token —
 * which is the entire point of caching something metered.
 */
public final class CachingExternalProvider<I, O> implements ExternalProvider<I, O> {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ExternalProvider<I, O> delegate;
    private final ExternalCacheStore store;
    private final ExternalKindSupport<I, O> support;
    private final ProviderConfig config;

    public CachingExternalProvider(ExternalProvider<I, O> delegate, ExternalCacheStore store,
                                   ExternalKindSupport<I, O> support, ProviderConfig config) {
        this.delegate = delegate;
        this.store = store;
        this.support = support;
        this.config = config;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return delegate.descriptor();
    }

    @Override
    public O call(I input, ProviderConfig callConfig) {
        Optional<String> key = cacheKey(input);
        if (key.isEmpty()) {
            return delegate.call(input, callConfig);
        }
        Optional<JsonNode> cached = store.get(key.get());
        if (cached.isPresent()) {
            return JSON.treeToValue(cached.get(), support.outputType());
        }
        O result = delegate.call(input, callConfig);
        store.put(key.get(), support.kind(), descriptor().id(), JSON.valueToTree(result),
                support.defaultCacheTtl());
        return result;
    }

    /** Empty when this provider forbids caching, or the kind says this input must never be cached. */
    private Optional<String> cacheKey(I input) {
        if (!descriptor().cacheable()) {
            return Optional.empty();
        }
        String identity = support.cacheIdentity(input);
        return identity == null
                ? Optional.empty()
                : Optional.of(ExternalCacheKey.of(support.kind(), descriptor().id(),
                        config.fingerprint(), identity));
    }

    @Override
    public ProbeResult probe(ProviderConfig probeConfig) {
        // Never cached: a probe exists to say whether the service is reachable *now*, and a remembered
        // answer to that question is worse than no answer.
        return delegate.probe(probeConfig);
    }

    /**
     * Delegated, not inherited.
     *
     * <p>{@link ExternalProvider}'s defaults are "one unit per call" and "the estimate"; a kind overrides
     * them with what it actually costs — a translation counts code points. A decorator that let the default
     * stand would report a 5,000-character call as one unit, and it is the decorator that every caller
     * reaches, so the override would never be consulted by anyone. Silent, and in the direction of
     * understating a bill.
     */
    @Override
    public long estimateUnits(I input) {
        return delegate.estimateUnits(input);
    }

    @Override
    public long actualUnits(I input, O output) {
        return delegate.actualUnits(input, output);
    }

}
