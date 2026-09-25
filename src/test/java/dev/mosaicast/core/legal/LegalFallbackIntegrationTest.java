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
import org.testcontainers.postgresql.PostgreSQLContainer;
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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

        // With the default set to English, which has no translation here, the page is still served in the
        // language it does have. It used to 404, so a page written only in German existed for German readers
        // and nobody else — and a hard load, which resolves the default locale, answered 404 for a page the
        // SPA then rendered (core#164).
        siteConfig.update(null, null, null, "en");
        assertThat(legal.render("imprint-fb", "fr").title()).isEqualTo("Impressum");
        assertThat(legal.footer("en")).extracting(LegalViews.FooterEntry::slug).contains("imprint-fb");
        // Crawlers are still told only the truth: the one language it is written in.
        assertThat(legal.translatedLocales("imprint-fb")).containsExactly("de");
    }

    @Test
    void aPageWithNoTranslationAtAllIsStillNotFound() {
        legal.createPage("empty-fb", null, 0);
        assertThatThrownBy(() -> legal.render("empty-fb", "en")).isInstanceOf(NotFoundException.class);
        assertThat(legal.footer("en")).extracting(LegalViews.FooterEntry::slug).doesNotContain("empty-fb");
    }

    @Test
    void aSlugOutsideTheGrammarIsRefusedAtCreation() {
        // `qa test/2` became a page no link reached and no delete could route to (core#164).
        for (String bad : new String[] {"qa test/2", "Privacy", "a--b", "-a", "a-", "ü", "a".repeat(65)}) {
            assertThatThrownBy(() -> legal.createPage(bad, null, 0))
                    .as(bad)
                    .isInstanceOf(dev.mosaicast.core.web.CodedBadRequest.class)
                    .satisfies(e -> assertThat(((dev.mosaicast.core.web.CodedBadRequest) e).code())
                            .isEqualTo("legal.slug.invalid"));
        }
        legal.createPage("terms-of-use-2", null, 0);
        legal.createPage("a".repeat(64), null, 0);
    }

    @Test
    void theAboutPageIsAuthoredHereButIsNotAFooterLegalLink() {
        // /about uses this CMS for its per-locale markdown and admin editor, but it is not a legal page —
        // listing it beside privacy and imprint would say something untrue about what it is. It gets its
        // own link in the footer and the info menu instead.
        legal.createPage("about-x", LegalService.ROLE_ABOUT, 100);
        legal.putTranslation("about-x", "en", "About this instance", "Two people and a microphone.");
        legal.createPage("terms-x", "terms", 30);
        legal.putTranslation("terms-x", "en", "Terms", "Some terms.");

        assertThat(legal.footer("en")).extracting(LegalViews.FooterEntry::slug).contains("terms-x");
        assertThat(legal.footer("en")).extracting(LegalViews.FooterEntry::slug).doesNotContain("about-x");

        // Excluded from the nav, still fully readable at its own URL — otherwise the page could not render.
        assertThat(legal.render("about-x", "en").title()).isEqualTo("About this instance");
    }

    @Test
    void theSeededPrivacyPageNoLongerClaimsACompletenessItsListsDoNotHave() {
        // "The complete, current list of what is stored" pointed at a list of browser storage while a
        // signed-in listener's email, name and playback position lived in the database (core#176).
        String en = legal.render("privacy", "en").html();
        String de = legal.render("privacy", "de").html();

        assertThat(en).doesNotContain("The complete, current list").contains("what it keeps in your");
        assertThat(de).doesNotContain("Die vollständige, aktuelle Liste").contains("in Ihrem");
    }
}
