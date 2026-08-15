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
 * @param meta         the scraper-facing OpenGraph/Twitter values; never {@code null}
 * @param canonicalUrl the absolute canonical URL for this view, or {@code null} to emit no canonical link
 * @param jsonLd       a JSON-LD object as a string, or {@code null} for none
 * @param noJsHtml     already-sanitized HTML for the no-JS content block, or {@code null} for none
 */
public record PageView(IndexHtmlService.Meta meta, String canonicalUrl, String jsonLd, String noJsHtml) {

    /** A view carrying meta only — what a plugin deep link produces, since the host cannot see its content. */
    public static PageView metaOnly(IndexHtmlService.Meta meta) {
        return new PageView(meta, null, null, null);
    }
}
