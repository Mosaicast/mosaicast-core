// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsManifest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Startup validation (ARCHITECTURE §12.7). A provider descriptor is first-party code compiled into the host,
 * so a wrong one is a bug that must not reach a running instance — unlike a plugin manifest, which is
 * third-party and gets skipped so the site survives it.
 */
class ExternalServiceRegistryTest {

    /** A provider that does nothing but carry a descriptor; the registry never calls one. */
    private record Stub(ProviderDescriptor descriptor) implements ExternalProvider<String, String> {

        @Override
        public String call(String input, ProviderConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProbeResult probe(ProviderConfig config) {
            return ProbeResult.ok("stub");
        }
    }

    private static ProviderDescriptor descriptor(String id, SettingsManifest settings) {
        return new ProviderDescriptor(id, ExternalServiceKind.TRANSLATION, "Stub", "A stub.",
                null, null, true, false, false, true, "characters", 60, Duration.ofSeconds(20), settings);
    }

    private static ExternalServiceRegistry registryOf(ProviderDescriptor... descriptors) {
        List<ExternalProvider<?, ?>> providers = new java.util.ArrayList<>();
        for (ProviderDescriptor descriptor : descriptors) {
            providers.add(new Stub(descriptor));
        }
        return new ExternalServiceRegistry(providers);
    }

    @Test
    void indexesProvidersByKindAndId() {
        ExternalServiceRegistry registry = registryOf(
                descriptor("libretranslate", SettingsManifest.empty()),
                descriptor("deepl", SettingsManifest.empty()));

        assertThat(registry.hasProviders(ExternalServiceKind.TRANSLATION)).isTrue();
        assertThat(registry.descriptors(ExternalServiceKind.TRANSLATION))
                .extracting(ProviderDescriptor::id).containsExactly("libretranslate", "deepl");
        assertThat(registry.provider(ExternalServiceKind.TRANSLATION, "deepl")).isPresent();
        assertThat(registry.provider(ExternalServiceKind.TRANSLATION, "nope")).isEmpty();
        assertThat(registry.provider(ExternalServiceKind.TRANSLATION, null)).isEmpty();
    }

    @Test
    void twoProvidersCannotClaimTheSameId() {
        assertThatThrownBy(() -> registryOf(
                descriptor("libretranslate", SettingsManifest.empty()),
                descriptor("libretranslate", SettingsManifest.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim the id");
    }

    @Test
    void aMalformedIdOrLimitFailsTheBoot() {
        assertThatThrownBy(() -> registryOf(descriptor("Libre Translate", SettingsManifest.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lower-case");

        ProviderDescriptor noBudget = new ProviderDescriptor("stub", ExternalServiceKind.TRANSLATION, "X", "",
                null, null, true, false, false, true, "characters", 0, Duration.ZERO, SettingsManifest.empty());
        assertThatThrownBy(() -> registryOf(noBudget))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("defaultRequestsPerMinute");
    }

    @Test
    void aSelectWithNoOptionsOrAnImpossibleDefaultFailsTheBoot() {
        assertThatThrownBy(() -> registryOf(descriptor("stub", SettingsManifest.of(
                SettingsField.select("format", "Format", "", "text", List.of())))))
                .hasMessageContaining("select needs options");

        assertThatThrownBy(() -> registryOf(descriptor("stub", SettingsManifest.of(
                SettingsField.select("format", "Format", "", "markdown",
                        List.of(new SettingsField.Option("text", "Plain")))))))
                .hasMessageContaining("not one of its options");
    }

    @Test
    void aDefaultOutsideItsOwnBoundsFailsTheBoot() {
        assertThatThrownBy(() -> registryOf(descriptor("stub", SettingsManifest.of(
                SettingsField.integer("rpm", "Requests per minute", "", 0, 1, 100)))))
                .hasMessageContaining("its own default must be at least 1");
    }

    @Test
    void anEnvSuffixOutsideTheNamespaceFailsTheBoot() {
        // The admin API answers "is this variable set?". A descriptor free to name any variable would turn
        // that endpoint into an oracle over MOSAICAST_DB_PASSWORD and everything else in the environment.
        assertThatThrownBy(() -> registryOf(descriptor("stub", SettingsManifest.of(
                SettingsField.envSecret("apiKey", "API key", "", "db_password")))))
                .hasMessageContaining("must be UPPER_SNAKE");

        assertThatCode(() -> registryOf(descriptor("stub", SettingsManifest.of(
                SettingsField.envSecret("apiKey", "API key", "", "API_KEY")))))
                .doesNotThrowAnyException();
    }

    @Test
    void aNonEnvFieldMayNotNameAVariable() {
        SettingsField sneaky = new SettingsField("apiKey", dev.mosaicast.core.external.settings
                .SettingsFieldType.STRING, "API key", "", null, false, null, null, List.of(),
                "DB_PASSWORD", null);

        assertThatThrownBy(() -> registryOf(descriptor("stub", SettingsManifest.of(sneaky))))
                .hasMessageContaining("only env-backed fields may name a variable");
    }

    @Test
    void aKindWithNoProvidersIsAnEmptySectionRatherThanAnError() {
        // With zero providers installed the admin page has to render "none available" instead of breaking.
        ExternalServiceRegistry registry = new ExternalServiceRegistry(List.of());

        assertThat(registry.hasProviders(ExternalServiceKind.TRANSLATION)).isFalse();
        assertThat(registry.descriptors(ExternalServiceKind.TRANSLATION)).isEmpty();
    }
}
