// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Renders legal-page markdown to <strong>sanitized</strong> HTML (ARCHITECTURE §12.6 — "rendered sanitized,
 * same care as wiki content"). CommonMark produces the HTML; jsoup then strips anything outside a relaxed
 * safelist (no scripts, no event handlers), so admin-authored markdown can never inject active content.
 */
@Component
public class MarkdownRenderer {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();
    private final Safelist safelist = Safelist.relaxed().addAttributes("a", "rel");

    /** Parses markdown to HTML and sanitizes it. */
    public String toSafeHtml(String markdown) {
        String rawHtml = renderer.render(parser.parse(markdown == null ? "" : markdown));
        return Jsoup.clean(rawHtml, safelist);
    }
}
