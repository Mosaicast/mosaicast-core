// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external.translation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.external.ExternalProvider;
import dev.mosaicast.core.external.http.ExternalHttpClient;
import dev.mosaicast.core.external.http.ExternalTargetPolicy;
import dev.mosaicast.core.external.settings.ProviderConfig;
import dev.mosaicast.core.external.translation.providers.LibreTranslateProvider;
import dev.mosaicast.core.feed.OutboundTargetPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The provider against a <strong>real</strong> LibreTranslate.
 *
 * <p>Skipped unless {@code -Dmosaicast.test.libretranslate-url=http://localhost:5000} is given, so CI —
 * which cannot reach one — never runs it. The loopback-stub tests beside this one carry the actual
 * coverage; this exists because a stub proves only that the code agrees with the author's idea of the
 * protocol, and the thing worth checking occasionally is whether that idea is right.
 *
 * <pre>
 *   ./gradlew test --tests '*LibreTranslateLiveTest' \
 *       -Dmosaicast.test.libretranslate-url=http://localhost:5000
 * </pre>
 */
@EnabledIfSystemProperty(named = "mosaicast.test.libretranslate-url", matches = ".+")
class LibreTranslateLiveTest {

    private static final String URL = System.getProperty("mosaicast.test.libretranslate-url", "");

    private static LibreTranslateProvider provider() {
        // The same allow-list an operator would configure: a self-hosted translator is a private origin
        // and has to be named explicitly.
        ExternalTargetPolicy targets = new ExternalTargetPolicy(new OutboundTargetPolicy(false, false), URL);
        return new LibreTranslateProvider(new ExternalHttpClient(targets));
    }

    private static ProviderConfig config() {
        return new ProviderConfig() {
            @Override
            public String string(String key) {
                return URL;
            }

            @Override
            public Optional<String> optionalString(String key) {
                return Optional.of(URL);
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

            /** Nothing set: the default instance runs {@code keyRequired: false}. */
            @Override
            public Optional<String> secret(String key) {
                return Optional.empty();
            }

            @Override
            public String fingerprint() {
                return "live";
            }
        };
    }

    @Test
    void probesTheRealService() {
        ExternalProvider.ProbeResult result = provider().probe(config());

        assertThat(result.ok()).as(result.detail()).isTrue();
        assertThat(result.detail()).contains("languages");
    }

    @Test
    void translatesWithNoCredential() {
        TranslationResult result =
                provider().call(TranslationRequest.of("The kraken surfaced at dawn.", "en", "nl"), config());

        assertThat(result.text()).isNotBlank().isNotEqualTo("The kraken surfaced at dawn.");
        assertThat(result.providerId()).isEqualTo(LibreTranslateProvider.ID);
    }

    @Test
    void detectsTheSourceLanguageWhenAsked() {
        TranslationResult result =
                provider().call(TranslationRequest.of("Der Kraken tauchte im Morgengrauen auf.", "en"), config());

        assertThat(result.detectedSourceLanguage()).isEqualTo("de");
        assertThat(result.text()).isNotBlank();
    }

    @Test
    void keepsMarkupWhenTheFormatSaysHtml() {
        TranslationResult result = provider().call(new TranslationRequest(
                "<p>The <b>kraken</b> surfaced.</p>", "en", "de", TranslationRequest.Format.HTML), config());

        assertThat(result.text()).contains("<b>").contains("</p>");
    }
}
