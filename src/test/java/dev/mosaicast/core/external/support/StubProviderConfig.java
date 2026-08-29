// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.support;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.ExternalServiceKind;
import dev.mosaicast.core.external.ProviderDescriptor;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.settings.SettingsField;
import dev.mosaicast.core.external.settings.SettingsManifest;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A provider that exists only so the admin surface has something to configure.
 *
 * <p><strong>Test scope, deliberately not a profile-gated bean in {@code main}.</strong> Dev-login is
 * profile-gated and that is fine, because its failure mode is visible. A fake service provider's failure mode
 * is a site quietly serving made-up results in production, and a profile is a runtime string somebody can get
 * wrong. Keeping it out of the artifact entirely is the only version that cannot be misconfigured.
 */
@TestConfiguration
public class StubProviderConfig {

    public static final String ID = "stub";

    @Bean
    ExternalProvider<String, String> stubProvider() {
        return new StubProvider();
    }

    /** Exercises every field type the admin form has to render. */
    static SettingsManifest manifest() {
        return SettingsManifest.of(
                SettingsField.requiredString("baseUrl", "Base URL", "Where the service lives.",
                        "http://localhost:5000"),
                // Optional on purpose: the real LibreTranslate runs keyRequired:false by default, and a
                // provider whose token was mandatory would be unusable on exactly that instance.
                SettingsField.secret("apiKey", "API key", "Only if this instance enforces one.", false),
                SettingsField.envSecret("adminKey", "Admin key", "From the environment.", "ADMIN_KEY", false),
                SettingsField.positiveInt("requestsPerMinute", "Requests per minute", "Politeness.", 60),
                SettingsField.select("format", "Format", "How the text is sent.", "text",
                        List.of(new SettingsField.Option("text", "Plain text"),
                                new SettingsField.Option("html", "HTML"))),
                SettingsField.bool("verify", "Verify TLS", "", true),
                SettingsField.info("note", "Note", "This provider is a test double."));
    }

    /** Answers from its own settings, so a test can prove resolution without a network. */
    public static class StubProvider implements ExternalProvider<String, String> {

        @Override
        public ProviderDescriptor descriptor() {
            return new ProviderDescriptor(ID, ExternalServiceKind.TRANSLATION, "Stub", "A test double.",
                    "https://example.invalid", null, true, false, false, true, "characters",
                    60, Duration.ofSeconds(5), manifest());
        }

        @Override
        public String call(String input, ProviderConfig config) {
            return "%s|%s".formatted(config.string("baseUrl"), input);
        }

        @Override
        public ProbeResult probe(ProviderConfig config) {
            return ProbeResult.ok("reachable at " + config.string("baseUrl"));
        }
    }
}
