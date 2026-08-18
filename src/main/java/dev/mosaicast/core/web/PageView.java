// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

/**
 * Everything the server knows about one URL before the SPA takes over (ARCHITECTURE §6.4/§6.6).
 *
 * <p>The four parts serve four different readers and are deliberately not collapsed into one:
 * {@code meta} is for link scrapers, which run no JS; {@code jsonLd} is for search engines, which want the
 * same facts as data; {@code noJsHtml} is for crawlers that render nothing at all (most AI crawlers); and
 * {@code canonicalUrl} tells all of them which of several filtered URLs is the real one. They are produced
 * together by {@link OgResolver} because they come from a single lookup, and injected together by
 * {@link IndexHtmlService}.
 *
 * <p>{@code shareUrl} is the fifth reader's answer, and the one place the identity of a <em>page</em> and
 * the identity of a <em>share</em> come apart: a timestamped episode link (§6.4) is the same page as the
 * episode — so the canonical URL stays bare — but it is not the same thing to have sent someone. See
 * {@code IndexHtmlService.headTags}.
 *
 * @param meta         the scraper-facing OpenGraph/Twitter values; never {@code null}
 * @param canonicalUrl the absolute canonical URL for this view, or {@code null} to emit no canonical link
 * @param shareUrl     the absolute URL as shared, for {@code og:url}; {@code null} to use the canonical one
 * @param jsonLd       a JSON-LD object as a string, or {@code null} for none
 * @param noJsHtml     already-sanitized HTML for the no-JS content block, or {@code null} for none
 */
public record PageView(IndexHtmlService.Meta meta, String canonicalUrl, String shareUrl, String jsonLd,
                       String noJsHtml) {

    /** A view whose shared URL is its canonical one — every view that is not a position inside a page. */
    public PageView(IndexHtmlService.Meta meta, String canonicalUrl, String jsonLd, String noJsHtml) {
        this(meta, canonicalUrl, null, jsonLd, noJsHtml);
    }

    /** A view carrying meta only — what a plugin deep link produces, since the host cannot see its content. */
    public static PageView metaOnly(IndexHtmlService.Meta meta) {
        return new PageView(meta, null, null, null);
    }

    /** The URL to advertise as {@code og:url}: what was shared, or the canonical URL when they agree. */
    @Override
    public String shareUrl() {
        return shareUrl == null ? canonicalUrl : shareUrl;
    }
}
