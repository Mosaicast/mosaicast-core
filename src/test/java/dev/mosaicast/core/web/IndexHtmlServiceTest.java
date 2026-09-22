// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import org.junit.jupiter.api.Test;

/**
 * How a resolved {@link PageView} lands in the served document (ARCHITECTURE §6.4/§6.6) — and, mostly, that
 * feed-derived text cannot break out of where it is put. Everything injected here is third-party content: a
 * podcaster does not write the episode titles in someone else's feed.
 */
class IndexHtmlServiceTest {

    private final IndexHtmlService indexHtml = new IndexHtmlService(siteConfig());

    private static SiteConfigService siteConfig() {
        SiteConfigService service = mock(SiteConfigService.class);
        SiteConfig config = mock(SiteConfig.class);
        when(config.getSiteName()).thenReturn("Test Cast");
        when(service.get()).thenReturn(config);
        return service;
    }

    @Test
    void metaTagsAndCanonicalLandInTheHead() {
        String html = indexHtml.render(new PageView(
                new IndexHtmlService.Meta("An Episode", "About things", "https://cdn.test/a.jpg"),
                "https://example.test/episodes/an-episode", null, null));

        assertThat(html).contains("<meta property=\"og:title\" content=\"An Episode\" />");
        assertThat(html).contains("<meta property=\"og:description\" content=\"About things\" />");
        assertThat(html).contains("<meta property=\"og:image\" content=\"https://cdn.test/a.jpg\" />");
        assertThat(html).contains(
                "<link rel=\"canonical\" href=\"https://example.test/episodes/an-episode\" />");
        assertThat(html).contains(
                "<meta property=\"og:url\" content=\"https://example.test/episodes/an-episode\" />");
        assertThat(html.indexOf("og:title")).isLessThan(html.indexOf("</head>"));
    }

    @Test
    void aTitleWithMarkupCannotEscapeItsAttribute() {
        String html = indexHtml.render(new PageView(
                new IndexHtmlService.Meta("\"><script>alert(1)</script>", "", null), null, null, null));

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&quot;&gt;&lt;script&gt;");
    }

    @Test
    void jsonLdCannotLeaveItsOwnScriptElement() {
        // A feed whose episode title contains `</script>` would otherwise end the data block early and have
        // the rest of the document's own markup parsed as page content.
        String html = indexHtml.render(new PageView(
                new IndexHtmlService.Meta("t", "", null), null,
                "{\"name\":\"evil </script><script>alert(1)</script>\"}", null));

        assertThat(html).contains("<script type=\"application/ld+json\">");
        assertThat(html).doesNotContain("</script><script>alert(1)");
        // Every `<` is escaped, not the `</` sequence alone — see below for why that is not enough.
        assertThat(html).contains("\\u003c/script>");
        // Exactly one opening and one closing tag for the data block.
        assertThat(html.split("<script type=\"application/ld\\+json\">", -1)).hasSize(2);
    }

    @Test
    void jsonLdCannotOpenTheDoubleEscapedState() {
        // The sequence the old escaping missed (core#196). `</` was neutralised, but the HTML tokenizer
        // also leaves script-data state on `<!--`, and a following `<script` puts it into the
        // double-escaped state — where this element's own `</script>` no longer ends it, and the rest of
        // the document, the shell's module script included, becomes the text content of this block. The
        // page then never mounts. An episode title from a third-party feed can carry both tokens.
        String html = indexHtml.render(new PageView(
                new IndexHtmlService.Meta("t", "", null), null,
                "{\"name\":\"<!-- <script> oops\"}", null));

        // Asserted on the block itself: the page template has HTML comments of its own, and what matters
        // is that neither token survives *inside* the JSON. `>` is deliberately left alone — it cannot
        // start anything, and escaping it would be noise.
        int opens = html.indexOf("application/ld+json\">") + "application/ld+json\">".length();
        // Searched from the block's own start: the shell has script tags of its own before this one.
        String block = html.substring(opens, html.indexOf("</script>", opens));
        assertThat(block).doesNotContain("<!--").doesNotContain("<script");
        assertThat(block).isEqualTo("{\"name\":\"\\u003c!-- \\u003cscript> oops\"}");
        // Still exactly one data block.
        assertThat(html.split("<script type=\"application/ld\\+json\">", -1)).hasSize(2);
    }

    @Test
    void theNoJsBlockGoesInsideTheMountPointSoReactReplacesIt() {
        String html = indexHtml.render(new PageView(
                new IndexHtmlService.Meta("t", "", null), null, null, "<h1>Readable</h1>"));

        // Inside `#root`: React's createRoot clears the container on mount, so the block needs no hand-off
        // code in the shell and cannot survive to be rendered twice.
        assertThat(html).contains("<div id=\"root\">\n<h1>Readable</h1></div>");
    }

    @Test
    void aViewWithNothingExtraStillServesTheShell() {
        String html = indexHtml.render(indexHtml.siteMeta());

        assertThat(html).contains("<meta property=\"og:title\" content=\"Test Cast\" />");
        assertThat(html).doesNotContain("rel=\"canonical\"");
        assertThat(html).doesNotContain("application/ld+json");
    }
}
