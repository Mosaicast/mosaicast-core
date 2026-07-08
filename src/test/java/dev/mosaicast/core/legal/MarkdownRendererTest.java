// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Risk-point tests for legal-page rendering (ARCHITECTURE §12.6, §13.5): markdown becomes HTML, and any
 * active content in the source is stripped by sanitization.
 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersMarkdownToHtml() {
        String html = renderer.toSafeHtml("# Title\n\nHello **world** and [a link](https://example.io).");
        assertThat(html).contains("<h1>Title</h1>");
        assertThat(html).contains("<strong>world</strong>");
        assertThat(html).contains("href=\"https://example.io\"");
    }

    @Test
    void stripsScriptTags() {
        String html = renderer.toSafeHtml("ok\n\n<script>alert('xss')</script>\n\n<img src=x onerror=alert(1)>");
        assertThat(html).doesNotContain("<script");
        assertThat(html).doesNotContain("alert('xss')");
        assertThat(html).doesNotContain("onerror");
    }

    @Test
    void stripsJavascriptHref() {
        String html = renderer.toSafeHtml("[click me](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }
}
