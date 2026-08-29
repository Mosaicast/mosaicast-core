// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.settings;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.external.ExternalServiceKind;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Credential lookup, and the namespace that keeps it from being an environment oracle (§12.7). */
class EnvProbeTest {

    @Test
    void derivesTheVariableNameFromKindProviderAndSuffix() {
        EnvProbe probe = new EnvProbe(new MockEnvironment());

        assertThat(probe.varName(ExternalServiceKind.TRANSLATION, "google", "API_KEY"))
                .isEqualTo("MOSAICAST_EXTERNAL_TRANSLATION_GOOGLE_API_KEY");
        // A dashed provider id is legal and must not produce a variable name nobody can export.
        assertThat(probe.varName(ExternalServiceKind.TRANSLATION, "libre-translate", "API_KEY"))
                .isEqualTo("MOSAICAST_EXTERNAL_TRANSLATION_LIBRE_TRANSLATE_API_KEY");
    }

    @Test
    void blankCountsAsUnset() {
        // An operator who wrote `KEY=` in their .env has not configured a provider, and a readiness check
        // that said otherwise would send an empty credential to a paid API.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MOSAICAST_EXTERNAL_TRANSLATION_GOOGLE_API_KEY", "   ");
        EnvProbe probe = new EnvProbe(environment);

        assertThat(probe.isSet(ExternalServiceKind.TRANSLATION, "google", "API_KEY")).isFalse();
    }

    @Test
    void readsAValueThatIsActuallySet() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MOSAICAST_EXTERNAL_TRANSLATION_GOOGLE_API_KEY", "sk-live-1234");
        EnvProbe probe = new EnvProbe(environment);

        assertThat(probe.isSet(ExternalServiceKind.TRANSLATION, "google", "API_KEY")).isTrue();
        assertThat(probe.value(ExternalServiceKind.TRANSLATION, "google", "API_KEY"))
                .contains("sk-live-1234");
    }

    @Test
    void onlySuffixesInsideTheNamespaceAreLegal() {
        assertThat(EnvProbe.isLegalSuffix("API_KEY")).isTrue();
        assertThat(EnvProbe.isLegalSuffix("KEY2")).isTrue();
        assertThat(EnvProbe.isLegalSuffix("api_key")).isFalse();
        assertThat(EnvProbe.isLegalSuffix("_KEY")).isFalse();
        assertThat(EnvProbe.isLegalSuffix("A".repeat(33))).isFalse();
        assertThat(EnvProbe.isLegalSuffix(null)).isFalse();
    }
}
