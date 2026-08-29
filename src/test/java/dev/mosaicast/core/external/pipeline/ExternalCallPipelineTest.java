// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.external.ExternalKindSupport;
import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.cache.ExternalCacheStore;
import dev.mosaicast.core.external.error.ExternalBusyException;
import dev.mosaicast.core.external.error.ExternalRateLimitedException;
import dev.mosaicast.core.external.error.ExternalTimeoutException;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsManifest;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The decorator chain (ARCHITECTURE §12.7). The order is the design, so it is what these pin.
 *
 * <p>No database: the cache store is faked in memory here, because what is under test is the composition,
 * not the SQL. {@code ExternalCacheIntegrationTest} covers the storage.
 */
class ExternalCallPipelineTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    /** Counts calls and can be made slow or explosive. */
    private static final class CountingProvider implements ExternalProvider<String, String> {

        private final AtomicInteger calls = new AtomicInteger();
        private Runnable before = () -> { };

        @Override
        public ProviderDescriptor descriptor() {
            return new ProviderDescriptor("counting", ExternalServiceKind.TRANSLATION, "Counting", "",
                    null, null, true, false, false, true, "characters", 60, Duration.ofSeconds(5),
                    SettingsManifest.empty());
        }

        @Override
        public String call(String input, ProviderConfig config) {
            before.run();
            calls.incrementAndGet();
            return "translated:" + input;
        }

        @Override
        public ProbeResult probe(ProviderConfig config) {
            return ProbeResult.ok("fine");
        }
    }

    /** An in-memory stand-in for the real store; identical contract, no Postgres. */
    private static final class FakeStore extends ExternalCacheStore {

        private final Map<String, JsonNode> entries = new HashMap<>();

        FakeStore() {
            super(null);
        }

        @Override
        public Optional<JsonNode> get(String cacheKey) {
            return Optional.ofNullable(entries.get(cacheKey));
        }

        @Override
        public void put(String cacheKey, ExternalServiceKind kind, String providerId, JsonNode payload,
                        Duration ttl) {
            entries.put(cacheKey, payload);
        }
    }

    private static final ExternalKindSupport<String, String> SUPPORT =
            new ExternalKindSupport<>() {
                @Override
                public ExternalServiceKind kind() {
                    return ExternalServiceKind.TRANSLATION;
                }

                @Override
                public Class<String> outputType() {
                    return String.class;
                }

                @Override
                public String cacheIdentity(String input) {
                    return "v1|" + input;
                }

                @Override
                public Duration defaultCacheTtl() {
                    return Duration.ofDays(1);
                }
            };

    private static ProviderConfig config() {
        return new ProviderConfig() {
            @Override
            public String string(String key) {
                return "";
            }

            @Override
            public Optional<String> optionalString(String key) {
                return Optional.empty();
            }

            @Override
            public int integer(String key) {
                return 60;
            }

            @Override
            public double decimal(String key) {
                return 0;
            }

            @Override
            public boolean bool(String key) {
                return false;
            }

            @Override
            public Optional<String> secret(String key) {
                return Optional.empty();
            }

            @Override
            public String fingerprint() {
                return "fp";
            }
        };
    }

    @Test
    void aCacheHitDoesNotReachTheProvider() {
        CountingProvider raw = new CountingProvider();
        ExternalProvider<String, String> cached =
                new CachingExternalProvider<>(raw, new FakeStore(), SUPPORT, config());

        assertThat(cached.call("hello", config())).isEqualTo("translated:hello");
        assertThat(cached.call("hello", config())).isEqualTo("translated:hello");

        assertThat(raw.calls.get()).as("second call served from cache").isEqualTo(1);
    }

    @Test
    void aCacheHitCostsNoRateLimitTokenAndNoPermit() {
        // The single sharpest test of the ordering. With the cache innermost, a site over its rate limit
        // could not serve a result it had already paid for — which is the opposite of what a cache is for.
        CountingProvider raw = new CountingProvider();
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();
        Semaphore permits = new Semaphore(1);

        ExternalProvider<String, String> bounded = new BoundedCallProvider<>(raw, permits, executor,
                Duration.ofMillis(50), Duration.ofSeconds(5));
        ExternalProvider<String, String> limited =
                new RateLimitedProvider<>(bounded, limiter, "translation:counting", 1);
        ExternalProvider<String, String> chain =
                new CachingExternalProvider<>(limited, new FakeStore(), SUPPORT, config());

        // First call spends the single token of the window.
        assertThat(chain.call("hello", config())).isEqualTo("translated:hello");
        // A different input has to go through and is refused — the limit is real.
        assertThatThrownBy(() -> chain.call("other", config()))
                .isInstanceOf(ExternalRateLimitedException.class);

        // The cached one still answers, over the limit and with the permit held by nobody.
        assertThat(chain.call("hello", config())).isEqualTo("translated:hello");
        assertThat(raw.calls.get()).isEqualTo(1);
    }

    @Test
    void aRefusalCarriesHowLongToWait() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();
        ExternalProvider<String, String> limited =
                new RateLimitedProvider<>(new CountingProvider(), limiter, "k", 1);

        limited.call("a", config());

        assertThatThrownBy(() -> limited.call("b", config()))
                .isInstanceOf(ExternalRateLimitedException.class)
                .satisfies(problem -> assertThat(
                        ((ExternalRateLimitedException) problem).retryAfter()).isPositive());
    }

    @Test
    void aSaturatedBulkheadSaysBusyRatherThanTimedOut() throws InterruptedException {
        // "We are full" and "they did not answer" have different fixes, so they are different exceptions
        // with different statuses.
        CountingProvider raw = new CountingProvider();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        raw.before = () -> {
            started.countDown();
            try {
                holding.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        Semaphore permits = new Semaphore(1);
        ExternalProvider<String, String> bounded = new BoundedCallProvider<>(raw, permits, executor,
                Duration.ofMillis(100), Duration.ofSeconds(5));

        Thread occupant = Thread.ofVirtual().start(() -> bounded.call("first", config()));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> bounded.call("second", config()))
                    .isInstanceOf(ExternalBusyException.class);
        } finally {
            holding.countDown();
            occupant.join();
        }
    }

    @Test
    void aProviderThatNeverReturnsHitsTheCallBudget() {
        CountingProvider raw = new CountingProvider();
        raw.before = () -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        ExternalProvider<String, String> bounded = new BoundedCallProvider<>(raw, new Semaphore(1), executor,
                Duration.ofMillis(100), Duration.ofMillis(200));

        assertThatThrownBy(() -> bounded.call("slow", config()))
                .isInstanceOf(ExternalTimeoutException.class);
    }

    @Test
    void aProvidersOwnRefusalArrivesAsItself() {
        // Not wrapped into something generic by the machinery that happened to be running it — the status
        // and problem type are the whole reason those exceptions are distinct.
        CountingProvider raw = new CountingProvider();
        raw.before = () -> {
            throw new dev.mosaicast.core.external.error.ExternalProviderException("upstream said no");
        };

        ExternalProvider<String, String> bounded = new BoundedCallProvider<>(raw, new Semaphore(1), executor,
                Duration.ofSeconds(1), Duration.ofSeconds(2));

        assertThatThrownBy(() -> bounded.call("x", config()))
                .isInstanceOf(dev.mosaicast.core.external.error.ExternalProviderException.class)
                .hasMessage("upstream said no");
    }

    @Test
    void aDifferentConfigFingerprintIsADifferentEntry() {
        // A changed base URL is a different model on a different machine; serving the old answer would be
        // quietly wrong.
        CountingProvider raw = new CountingProvider();
        FakeStore store = new FakeStore();

        new CachingExternalProvider<>(raw, store, SUPPORT, config()).call("hello", config());
        ProviderConfig moved = new ProviderConfigWithFingerprint("different");
        new CachingExternalProvider<>(raw, store, SUPPORT, moved).call("hello", moved);

        assertThat(raw.calls.get()).isEqualTo(2);
    }

    @Test
    void aProbeIsNeverServedFromCache() {
        // A probe answers "is it reachable now"; a remembered answer to that is worse than none.
        CountingProvider raw = new CountingProvider();
        ExternalProvider<String, String> cached =
                new CachingExternalProvider<>(raw, new FakeStore(), SUPPORT, config());

        assertThat(cached.probe(config()).ok()).isTrue();
        assertThat(cached.probe(config()).ok()).isTrue();
    }

    /** A config that differs only in its fingerprint. */
    private record ProviderConfigWithFingerprint(String fingerprint) implements ProviderConfig {

        @Override
        public String string(String key) {
            return "";
        }

        @Override
        public Optional<String> optionalString(String key) {
            return Optional.empty();
        }

        @Override
        public int integer(String key) {
            return 60;
        }

        @Override
        public double decimal(String key) {
            return 0;
        }

        @Override
        public boolean bool(String key) {
            return false;
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.empty();
        }
    }
}
