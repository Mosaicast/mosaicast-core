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

/**
 * The dynamic {@code sitemap.xml} (ARCHITECTURE §6.6): every public URL the host knows — episodes, feed
 * views, legal pages — plus the entries active plugins contribute through the optional
 * {@code SitemapProvider} (§7.4), which is what makes plugin deep links discoverable.
 *
 * <p>Locations are absolute and built from {@code mosaicast.base-url}, not from the request — see
 * {@link SiteUrls}, which owns that rule and the reason for it.
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

    private final SiteUrls urls;
    private final dev.mosaicast.core.i18n.LocaleRegistry locales;

    public SitemapController(EpisodeQueryService episodes, FeedService feeds, LegalService legal,
                             SiteConfigService siteConfig, PluginExtensions extensions, SiteUrls urls,
                             dev.mosaicast.core.i18n.LocaleRegistry locales) {
        this.episodes = episodes;
        this.feeds = feeds;
        this.legal = legal;
        this.siteConfig = siteConfig;
        this.extensions = extensions;
        this.urls = urls;
        this.locales = locales;
    }

    @GetMapping(path = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap() {
        String base = urls.base();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("/", null, uiLocaleCodes()));

        for (FeedView feed : feeds.list()) {
            // The public URL is the slug; a feed still awaiting its boot backfill has none and is skipped
            // rather than advertised under an id that is no longer its address.
            if (feed.enabled() && feed.slug() != null) {
                entries.add(new Entry("/feeds/" + feed.slug(), null, uiLocaleCodes()));
            }
        }
        episodes.listSite(null, null, null, true, PageRequest.of(0, MAX_EPISODES)).getContent().stream()
                .map(EpisodeSummary::slug)
                .filter(slug -> slug != null && !slug.isBlank())
                .forEach(slug -> entries.add(new Entry("/episodes/" + slug, null, uiLocaleCodes())));

        // Every legal page once, with its own alternates: these are the one core surface whose *text*
        // differs per language, and listing only the default locale's set is why a German-only imprint was
        // never in the sitemap at all.
        for (FooterEntry page : legal.footer(siteConfig.get().getDefaultLocale())) {
            entries.add(new Entry("/legal/" + page.slug(), null,
                    uiSubset(legal.translatedLocales(page.slug()))));
        }
        for (SitemapUrl url : extensions.sitemapUrls()) {
            // No alternates: a plugin has no way to declare its translation group yet — SitemapUrl carries
            // only a loc and a timestamp. Emitting the site's UI locales here would be the host inventing a
            // claim about a plugin's content that the plugin never made.
            entries.add(new Entry(url.loc(), url.lastModified(), List.of()));
        }

        StringBuilder xml = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n"
                        + "        xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        for (Entry entry : entries) {
            String bare = base + entry.path();
            xml.append("  <url>\n    <loc>").append(escape(bare)).append("</loc>\n");
            if (entry.lastModified() != null) {
                xml.append("    <lastmod>").append(entry.lastModified()).append("</lastmod>\n");
            }
            // An alternate set has to be reciprocal and self-referential to be believed: every URL in the
            // group lists every URL in the group, itself included. Emitting them on the bare URL only, as
            // one block per entry, satisfies that without listing each translation as its own <url> — which
            // would be the same page indexed n times.
            for (String locale : entry.alternates()) {
                xml.append(alternate(locale, base + entry.path(), locale));
            }
            if (!entry.alternates().isEmpty()) {
                // x-default is what a crawler serves someone whose language is not in the set. The bare URL
                // is exactly that: no `lang`, so the site's own default answers.
                xml.append(alternate("x-default", bare, null));
            }
            xml.append("  </url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }

    /** One {@code xhtml:link}; {@code lang} null means the bare URL, which is what x-default points at. */
    private String alternate(String hreflang, String href, String lang) {
        String target = lang == null || isDefault(lang)
                ? href : href + "?" + SiteUrls.canonicalQuery(lang, null, null, null);
        return "    <xhtml:link rel=\"alternate\" hreflang=\"" + escape(hreflang)
                + "\" href=\"" + escape(target) + "\" />\n";
    }

    /** The site's UI languages as codes — the set every core page is reachable in. */
    private List<String> uiLocaleCodes() {
        return locales.uiLocales().stream().map(dev.mosaicast.core.i18n.LocaleInfo::code).toList();
    }

    /**
     * Those of {@code candidates} the shell can actually render in.
     *
     * <p>A legal page may be authored in a content language that has no catalog (§12.7) — a Dutch imprint on
     * an English-only site is the documented case. There is no {@code ?lang=nl} to point at, because the
     * shell cannot render Dutch, so advertising one would be an alternate that resolves to the default and
     * silently contradicts its own {@code hreflang}.
     */
    private List<String> uiSubset(List<String> candidates) {
        List<String> ui = uiLocaleCodes();
        return candidates.stream().filter(ui::contains).toList();
    }

    private boolean isDefault(String locale) {
        return locales.isDefaultLocale(locale);
    }

    /**
     * One sitemap entry: a root-relative path, an optional last-modified stamp, and the languages this URL
     * is reachable in.
     *
     * @param alternates UI locale codes for {@code hreflang}; empty for a URL that makes no such claim
     */
    private record Entry(String path, Instant lastModified, List<String> alternates) {
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
