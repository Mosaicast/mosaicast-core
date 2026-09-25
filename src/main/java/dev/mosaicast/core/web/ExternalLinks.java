// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.net.URI;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The one link policy for third-party HTML, server side (core#169).
 *
 * <p>Show notes are rendered twice — by the shell, and into the no-JS block a crawler reads — and the two
 * disagreed: jsoup's {@code Safelist.basic()} forced {@code rel="nofollow"} onto every link, the shell added
 * nothing, so the operator endorsed a link on one rendering and not on the other. Both now write exactly
 * this, and the frontend's {@code EXTERNAL_LINK_REL} is the same string.
 *
 * <p>Only links that <em>leave</em> the site: one to the site's own origin is the operator's, and in the shell
 * it is handed to the router rather than a new tab.
 */
public final class ExternalLinks {

    /**
     * {@code noopener noreferrer} is required with {@code target="_blank"}; {@code nofollow ugc} says the
     * markup is a third party's, published through the site rather than written by its operator.
     */
    public static final String REL = "noopener noreferrer nofollow ugc";

    private ExternalLinks() {
    }

    /**
     * Marks every link in already-sanitized HTML that points away from {@code siteBase}.
     *
     * @param cleanHtml HTML that has been through a jsoup safelist — this adds attributes, it cleans nothing
     * @param siteBase  the site's base URL, whose origin counts as internal
     * @return the same fragment, with {@code target} and {@code rel} set on external links and removed from
     *         every other one, so nothing the feed supplied survives
     */
    public static String mark(String cleanHtml, String siteBase) {
        Document fragment = Jsoup.parseBodyFragment(cleanHtml);
        String site = originOf(siteBase);
        for (Element link : fragment.select("a[href]")) {
            String origin = originOf(link.attr("href"));
            boolean external = origin != null && !origin.equals(site)
                    && (origin.startsWith("http://") || origin.startsWith("https://"));
            if (external) {
                link.attr("target", "_blank").attr("rel", REL);
            } else {
                link.removeAttr("target").removeAttr("rel");
            }
        }
        fragment.outputSettings().prettyPrint(false);
        return fragment.body().html();
    }

    /** {@code scheme://host[:port]} in lower case, or null for anything that is not an absolute URL. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
