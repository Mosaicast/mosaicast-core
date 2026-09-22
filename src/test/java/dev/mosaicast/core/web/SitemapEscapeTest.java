// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The sitemap's XML escaping (ARCHITECTURE §6.6).
 *
 * <p>Worth its own test because the values are not all host-generated: a plugin's
 * {@code SitemapUrl.alternates()} supplies both halves of an {@code <xhtml:link>}, and those go into
 * double-quoted <em>attributes</em>. Escaping only {@code &}, {@code <} and {@code >} is enough for element
 * content and not for an attribute, which is the gap this closes (core#181).
 */
class SitemapEscapeTest {

    @Test
    void theThreeMarkupCharactersAreStillEscaped() {
        assertThat(SitemapController.escape("a&b")).isEqualTo("a&amp;b");
        assertThat(SitemapController.escape("<tag>")).isEqualTo("&lt;tag&gt;");
    }

    @Test
    void aQuoteCannotEndAnAttribute() {
        // `hreflang` and `href` are written as hreflang="…" href="…". A value carrying a double quote
        // would close the attribute and let whatever follows be read as markup, in a document the browser
        // parses as XML in the XHTML namespace this sitemap declares.
        assertThat(SitemapController.escape("de\" onload=\"x"))
                .isEqualTo("de&quot; onload=&quot;x")
                .doesNotContain("\"");
        assertThat(SitemapController.escape("it's")).isEqualTo("it&apos;s");
    }

    @Test
    void ampersandIsEscapedFirstSoNothingIsDoubleEscaped() {
        // If `&` were escaped after `<`, the `&` of `&lt;` would itself be rewritten and the output would
        // read `&amp;lt;`.
        assertThat(SitemapController.escape("&<>\"'")).isEqualTo("&amp;&lt;&gt;&quot;&apos;");
    }
}
