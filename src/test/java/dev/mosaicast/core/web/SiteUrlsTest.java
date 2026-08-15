// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The canonical-URL rules of §6.6. Filter state lives in the query string (§6.1), so the same view is
 * reachable under many URLs — which is exactly the duplicate content {@code rel=canonical} has to collapse
 * to one form.
 */
class SiteUrlsTest {

    private final SiteUrls urls = new SiteUrls("https://example.test/");

    @Test
    void baseUrlLosesItsTrailingSlashSoPathsDoNotDouble() {
        assertThat(urls.base()).isEqualTo("https://example.test");
        assertThat(urls.absolute("/episodes/a")).isEqualTo("https://example.test/episodes/a");
        assertThat(urls.absolute("/")).isEqualTo("https://example.test/");
    }

    @Test
    void filterOrderDoesNotChangeTheCanonicalForm() {
        // Same filter state, two orders a visitor's URL could have been built in — one canonical answer.
        assertThat(SiteUrls.canonicalQuery("2", "christmas", null)).isEqualTo("season=2&tag=christmas");
        assertThat(SiteUrls.canonicalQuery("2", "christmas", "newest")).isEqualTo("season=2&tag=christmas");
    }

    @Test
    void theDefaultOrderIsDroppedButAnExplicitOneIsKept() {
        // `newest` is what the shell assumes when the parameter is absent, so keeping it would canonicalize
        // one view two ways.
        assertThat(SiteUrls.canonicalQuery(null, null, "newest")).isEmpty();
        assertThat(SiteUrls.canonicalQuery(null, null, "oldest")).isEqualTo("order=oldest");
    }

    @Test
    void unfilteredViewsCanonicalizeToTheBarePath() {
        assertThat(SiteUrls.canonicalQuery(null, null, null)).isEmpty();
        assertThat(SiteUrls.canonicalQuery("", "  ", "")).isEmpty();
        assertThat(urls.absolute("/", SiteUrls.canonicalQuery(null, null, null)))
                .isEqualTo("https://example.test/");
    }

    @Test
    void tagValuesAreUrlEncoded() {
        assertThat(SiteUrls.canonicalQuery(null, "hello world & more", null))
                .isEqualTo("tag=hello+world+%26+more");
    }
}
