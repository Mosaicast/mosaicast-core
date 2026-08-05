// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.feed.FeedService;
import dev.mosaicast.core.feed.FeedView;
import dev.mosaicast.core.legal.LegalViews.FooterEntry;
import dev.mosaicast.core.legal.LegalService;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The dynamic {@code sitemap.xml} (ARCHITECTURE §6.6): every public URL the host knows — episodes, feed
 * views, legal pages — plus the entries active plugins contribute through the optional
 * {@code SitemapProvider} (§7.4), which is what makes plugin deep links discoverable.
 *
 * <p>Locations are absolute, built from the request's own base URL, so the same instance is correct behind
 * any host name without configuration. {@code robots.txt}, the admin-configurable AI-crawler policy and
 * JSON-LD are the rest of §6.6 and land with M6.
 */
@RestController
public class SitemapController {

    /** Upper bound on episode URLs in one sitemap; the protocol's own limit is 50 000. */
    private static final int MAX_EPISODES = 5_000;

    private final EpisodeQueryService episodes;
    private final FeedService feeds;
    private final LegalService legal;
    private final SiteConfigService siteConfig;
    private final PluginExtensions extensions;

    public SitemapController(EpisodeQueryService episodes, FeedService feeds, LegalService legal,
                             SiteConfigService siteConfig, PluginExtensions extensions) {
        this.episodes = episodes;
        this.feeds = feeds;
        this.legal = legal;
        this.siteConfig = siteConfig;
        this.extensions = extensions;
    }

    @GetMapping(path = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap() {
        String base = baseUrl();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("/", null));

        for (FeedView feed : feeds.list()) {
            // The public URL is the slug; a feed still awaiting its boot backfill has none and is skipped
            // rather than advertised under an id that is no longer its address.
            if (feed.enabled() && feed.slug() != null) {
                entries.add(new Entry("/feeds/" + feed.slug(), null));
            }
        }
        episodes.listSite(null, null, null, true, PageRequest.of(0, MAX_EPISODES)).getContent().stream()
                .map(EpisodeSummary::slug)
                .filter(slug -> slug != null && !slug.isBlank())
                .forEach(slug -> entries.add(new Entry("/episodes/" + slug, null)));

        for (FooterEntry page : legal.footer(siteConfig.get().getDefaultLocale())) {
            entries.add(new Entry("/legal/" + page.slug(), null));
        }
        for (SitemapUrl url : extensions.sitemapUrls()) {
            entries.add(new Entry(url.loc(), url.lastModified()));
        }

        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (Entry entry : entries) {
            xml.append("  <url>\n    <loc>").append(escape(base + entry.path())).append("</loc>\n");
            if (entry.lastModified() != null) {
                xml.append("    <lastmod>").append(entry.lastModified()).append("</lastmod>\n");
            }
            xml.append("  </url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    /** One sitemap entry: a root-relative path plus an optional last-modified stamp. */
    private record Entry(String path, Instant lastModified) {
    }

    private static String baseUrl() {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
