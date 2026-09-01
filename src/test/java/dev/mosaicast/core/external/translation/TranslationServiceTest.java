// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ExternalServices;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.pipeline.BoundedCallProvider;
import dev.mosaicast.core.external.pipeline.RateLimitedProvider;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsManifest;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TranslationService} against a provider that has been through the pipeline — which is the only
 * shape it is ever handed one in.
 *
 * <p>This class exists because of a bug it would have caught on the day it was written. The service used to
 * cast to {@link TranslationProvider}, and {@link ExternalServices.Resolved#provider()} is the <em>wrapped</em>
 * provider: a bulkhead around a rate limiter around, sometimes, a cache, none of which implement a kind's
 * interface. So every call threw {@link ClassCastException} — on the plugin endpoint, on a plugin's backend
 * handle, in the legal-page prefill and in {@code draftCatalog} alike. Nothing caught it because nothing
 * tested this class at all, and the one test that could have — a provider unit test — calls the raw bean,
 * which is exactly the object the bug is not about.
 *
 * <p>Hence the deliberate shape here: the provider under test is <strong>wrapped before it is handed over</strong>.
 * A test that resolved the raw provider would pass against the broken code and prove nothing.
 */
class TranslationServiceTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void translatesThroughAProviderThePipelineHasWrapped() {
        RecordingProvider raw = new RecordingProvider();
        TranslationService service = new TranslationService(resolving(wrap(raw)));

        TranslationResult result = service.translate(TranslationRequest.of("The lighthouse", "de"));

        assertThat(result.text()).isEqualTo("[de] The lighthouse");
        assertThat(result.providerId()).isEqualTo("recording");
        assertThat(raw.seen).isEqualTo("The lighthouse");
    }

    @Test
    void theWrappedProviderIsNotTheKindsInterface() {
        // The invariant behind the fix, stated so it cannot quietly stop being true. A wrapper implements
        // ExternalProvider and nothing else, on purpose — it is generic over <I, O> and knows nothing about
        // translation — so a caller must reach it through `callable()`, never through a kind cast.
        ExternalProvider<TranslationRequest, TranslationResult> wrapped = wrap(new RecordingProvider());

        assertThat(wrapped).isNotInstanceOf(TranslationProvider.class);
        assertThat(wrapped.call(TranslationRequest.of("hello", "de"), config()).text())
                .isEqualTo("[de] hello");
    }

    @Test
    void theKindsUnitAccountingSurvivesTheWrapping() {
        // A translation is billed per character, which TranslationProvider overrides ExternalProvider's
        // one-unit default to say. The decorator is what every caller holds, so a decorator that inherited
        // the default instead of delegating would report a 5,000-character call as one unit — silent, and
        // in the direction of understating a bill.
        TranslationRequest request = TranslationRequest.of("The lighthouse", "de");
        RecordingProvider raw = new RecordingProvider();

        assertThat(wrap(raw).estimateUnits(request)).isEqualTo(raw.estimateUnits(request)).isEqualTo(14);
    }

    /** The chain {@code ExternalCallPipeline} builds, minus the cache, which needs a store. */
    private ExternalProvider<TranslationRequest, TranslationResult> wrap(TranslationProvider raw) {
        ExternalProvider<TranslationRequest, TranslationResult> bounded = new BoundedCallProvider<>(raw,
                new Semaphore(4), executor, Duration.ofMillis(200), Duration.ofSeconds(5));
        return new RateLimitedProvider<>(bounded, new FixedWindowRateLimiter(), "translation:recording", 60);
    }

    /** An {@link ExternalServices} that always resolves to the given (already wrapped) provider. */
    private static ExternalServices resolving(ExternalProvider<TranslationRequest, TranslationResult> provider) {
        ExternalServices services = mock(ExternalServices.class);
        ExternalServices.Resolved resolved =
                new ExternalServices.Resolved(DESCRIPTOR, provider, config());
        when(services.require(ExternalServiceKind.TRANSLATION)).thenReturn(resolved);
        return services;
    }

    private static final ProviderDescriptor DESCRIPTOR = new ProviderDescriptor("recording",
            ExternalServiceKind.TRANSLATION, "Recording", "", null, null, true, false, false, true,
            "characters", 60, Duration.ofSeconds(5), SettingsManifest.empty());

    /** A translation provider that answers predictably and remembers what it was asked. */
    private static final class RecordingProvider implements TranslationProvider {

        private String seen;

        @Override
        public ProviderDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public TranslationResult call(TranslationRequest input, ProviderConfig config) {
            seen = input.text();
            return new TranslationResult("[%s] %s".formatted(input.to(), input.text()), null, "recording",
                    false);
        }

        @Override
        public ProbeResult probe(ProviderConfig config) {
            return ProbeResult.ok("stub");
        }
    }

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
            public java.util.Optional<String> secret(String key) {
                return Optional.empty();
            }

            @Override
            public String fingerprint() {
                return "fp";
            }
        };
    }
}
