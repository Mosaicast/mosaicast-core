// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

/**
 * The no-JS copy of show notes agrees with the shell where the shared feed policy says it should.
 */
class ShowNotesSafelistTest {

    @Test
    void aResumedNumberedListKeepsItsStartNumber() {
        // SDK 0.16.1 allows `start`: without it `<ol start="3">` renumbers from 1 and changes what it says.
        String html = Jsoup.clean("<ol start=\"3\"><li>three</li></ol>", OgResolver.SHOW_NOTES);

        assertThat(html).contains("<ol start=\"3\">");
    }

    @Test
    void theAttributesTheFeedPolicyLeavesOutStayOut() {
        // The shell drops data-*, aria-*, style and handlers (core#232); the server copy must not keep them.
        String html = Jsoup.clean(
                "<p data-x=\"1\" aria-label=\"y\" style=\"position:fixed\" onclick=\"x()\">p</p>",
                OgResolver.SHOW_NOTES);

        assertThat(html).isEqualTo("<p>p</p>");
    }
}
