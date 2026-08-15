// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where this site canonically lives, and how to build absolute URLs against it (ARCHITECTURE §6.6).
 *
 * <p><strong>The base URL comes from configuration, never from the request.</strong> That rule is the whole
 * reason this class exists as one place rather than three. {@link SitemapController} used to derive it with
 * {@code ServletUriComponentsBuilder.fromCurrentContextPath()}, which reads the host off the request; with
 * {@code server.forward-headers-strategy: framework} (application.yml) that makes {@code X-Forwarded-Host}
 * authoritative, and nothing checked the result was one of the site's own names. A single
 * {@code curl -H 'X-Forwarded-Host: evil.example' https://site/sitemap.xml} therefore returned a sitemap
 * whose every {@code <loc>} pointed at the attacker's host — handed to a crawler, that is the site's own
 * canonical URLs reassigned to somebody else. The shipped compose file exposes the app port directly with no
 * proxy in front to strip the header.
 *
 * <p>The same hole is worth exactly as much in a {@code rel=canonical} link, in {@code hreflang}, in an
 * {@code og:image} and in {@code robots.txt}'s sitemap reference — a self-referential statement about the
 * site's identity is precisely what an attacker wants to be able to set. So every one of them resolves
 * through here, and none of them may reach for the request.
 */
@Component
public class SiteUrls {

    private final String baseUrl;

    public SiteUrls(@Value("${mosaicast.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = trimTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
    }

    /** The site's absolute base URL, without a trailing slash. */
    public String base() {
        return baseUrl;
    }

    /**
     * An absolute URL for a root-relative path.
     *
     * @param path a path starting with {@code /}; {@code "/"} yields the base URL plus a trailing slash
     * @return the absolute URL
     */
    public String absolute(String path) {
        if (path == null || path.isBlank()) {
            return baseUrl + "/";
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * An absolute URL for a path plus an already-normalized query string.
     *
     * @param path  a root-relative path
     * @param query the query string without its leading {@code ?}; empty or null for none
     * @return the absolute URL
     */
    public String absolute(String path, String query) {
        String url = absolute(path);
        return query == null || query.isBlank() ? url : url + "?" + query;
    }

    /**
     * Renders filter parameters into a canonical query string: a fixed key order, defaults omitted, and
     * anything not a filter axis dropped (§6.1/§6.6 — "normalize filter query params").
     *
     * <p>The order matters as much as the content. Two links to the same filtered view differing only in
     * parameter order are the duplicate content {@code rel=canonical} exists to collapse, so the canonical
     * form has to be a function of the filter state rather than of how a visitor's URL happened to be built.
     *
     * @param season the season filter, or null/blank for none
     * @param tag    the tag filter, or null/blank for none
     * @param order  {@code oldest} to keep; {@code newest} is the default and is dropped
     * @return the normalized query string without a leading {@code ?}; empty when nothing is filtered
     */
    public static String canonicalQuery(String season, String tag, String order) {
        Map<String, String> params = new LinkedHashMap<>();
        if (season != null && !season.isBlank()) {
            params.put("season", season.trim());
        }
        if (tag != null && !tag.isBlank()) {
            params.put("tag", tag.trim());
        }
        // `newest` is what the shell falls back to when the parameter is absent (EpisodeFeed.tsx), so
        // carrying it would make the same view canonicalize two ways.
        if ("oldest".equalsIgnoreCase(order == null ? "" : order.trim())) {
            params.put("order", "oldest");
        }
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(key).append('=').append(urlEncode(value));
        });
        return query.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
