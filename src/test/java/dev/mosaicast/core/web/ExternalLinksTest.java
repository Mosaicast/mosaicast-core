// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.junit.jupiter.api.Test;

/** One link policy for show notes, whichever side renders them (core#169). */
class ExternalLinksTest {

    private static final String SITE = "https://podcast.example";

    private static Element only(String html) {
        return Jsoup.parseBodyFragment(ExternalLinks.mark(html, SITE)).selectFirst("a");
    }

    @Test
    void aLinkLeavingTheSiteOpensANewTabUnendorsed() {
        Element link = only("<a href=\"https://publisher.example/notes\">notes</a>");

        assertThat(link.attr("target")).isEqualTo("_blank");
        assertThat(link.attr("rel")).isEqualTo(ExternalLinks.REL).isEqualTo("noopener noreferrer nofollow ugc");
    }

    @Test
    void theSitesOwnOriginIsNotExternalWhateverTheCaseOrDefaultPort() {
        for (String href : new String[] {"https://podcast.example/episodes/x", "HTTPS://Podcast.Example:443/y"}) {
            Element link = only("<a href=\"" + href + "\">x</a>");
            assertThat(link.hasAttr("target")).as(href).isFalse();
            assertThat(link.hasAttr("rel")).as(href).isFalse();
        }
    }

    @Test
    void anotherPortOrSchemeIsAnotherOrigin() {
        assertThat(only("<a href=\"https://podcast.example:8443/x\">x</a>").attr("target")).isEqualTo("_blank");
        assertThat(only("<a href=\"http://podcast.example/x\">x</a>").attr("target")).isEqualTo("_blank");
    }

    @Test
    void nothingTheFeedSuppliedSurvives() {
        // `rel="opener"` would hand the opened page this one; `target="_self"` would put the navigation back.
        Element external = only("<a href=\"https://publisher.example\" target=\"_self\" rel=\"opener\">x</a>");
        Element mail = only("<a href=\"mailto:hi@example.com\" target=\"_top\" rel=\"opener\">x</a>");

        assertThat(external.attr("target")).isEqualTo("_blank");
        assertThat(external.attr("rel")).isEqualTo(ExternalLinks.REL);
        assertThat(mail.hasAttr("target")).isFalse();
        assertThat(mail.hasAttr("rel")).isFalse();
    }

    @Test
    void theShowNotesSafelistNoLongerForcesItsOwnNofollow() {
        // `Safelist.basic()` enforces rel="nofollow" on every link — a second policy, disagreeing with the
        // shell's on the site's own links. Cleaned without it, then marked, there is only one.
        Safelist showNotes = Safelist.basic().removeEnforcedAttribute("a", "rel");
        String cleaned = Jsoup.clean("<a href=\"https://podcast.example/x\">self</a>", showNotes);

        assertThat(only(cleaned).hasAttr("rel")).isFalse();
    }
}
