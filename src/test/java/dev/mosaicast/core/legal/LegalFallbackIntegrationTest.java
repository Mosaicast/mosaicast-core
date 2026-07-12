// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.web.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The legal-page fallback follows the configured site default language (ARCHITECTURE §12.7): a page with no
 * translation in the requested locale renders in the site default, and 404s only when even that is missing.
 */
@SpringBootTest
@Testcontainers
class LegalFallbackIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LegalService legal;

    @Autowired
    private SiteConfigService siteConfig;

    @Test
    void missingTranslationFallsBackToTheConfiguredDefaultLocale() {
        legal.createPage("imprint-fb", "imprint", 0);
        legal.putTranslation("imprint-fb", "de", "Impressum", "# Impressum\n\nInhalt.");

        // With the site default set to German, an unsupported locale falls back to the German page.
        siteConfig.update(null, null, null, "de");
        assertThat(legal.render("imprint-fb", "fr").title()).isEqualTo("Impressum");

        // With the default set to English (which has no translation here), the same request 404s.
        siteConfig.update(null, null, null, "en");
        assertThatThrownBy(() -> legal.render("imprint-fb", "fr")).isInstanceOf(NotFoundException.class);
    }
}
