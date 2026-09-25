// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import dev.mosaicast.core.branding.BrandingAsset;
import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.episode.EpisodeDetail;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.feed.FeedDetailView;
import dev.mosaicast.core.feed.FeedService;
import dev.mosaicast.core.legal.LegalService;
import dev.mosaicast.core.legal.LegalViews.RenderedPage;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves one URL into everything a non-JS reader needs (ARCHITECTURE §6.4/§6.6): OpenGraph/Twitter meta,
 * JSON-LD, a readable content block and a canonical URL.
 *
 * <p>The shell is an SPA, but link scrapers and most AI crawlers do not run JS, so whatever they are to see
 * has to be in the HTML the server returns. This is the core-route counterpart of what
 * {@code PluginPageController} already does for {@code /p/{pluginId}/*} through a plugin's
 * {@code ShareMetadataProvider} — same seam, same {@link IndexHtmlService}, but here the host owns the data
 * and can therefore also answer with structured data and real content.
 *
 * <p><strong>Filters are part of the identity of a view</strong> (§6.1): the season and tag live in the query
 * string, so a shared filtered link has to preview as that filtered view, and its canonical URL has to be the
 * normalized form of those same filters ({@link SiteUrls#canonicalQuery}) rather than the bare path.
 *
 * <p>A missing episode, feed or legal page throws {@link NotFoundException} — §6.6 rules out soft-404s, so
 * the answer for an unknown slug must be a real 404 and not a 200 carrying site-level metadata.
 */
@Service
public class OgResolver {

    /** How many episodes the no-JS block lists on a site/feed page — enough to crawl, not a full dump. */
    private static final int NO_JS_EPISODES = 30;

    /** Characters of description surfaced in a link preview; scrapers truncate around here anyway. */
    private static final int DESCRIPTION_LIMIT = 300;

    /**
     * What show notes may contain in the no-JS block. {@code basic()} keeps the text structure crawlers came
     * for (paragraphs, lists, links, emphasis) and drops everything else — including {@code <img>}: feed
     * descriptions are third-party HTML, and an image there is an arbitrary-origin request the visitor never
     * asked for, which is the same one-way beacon §12.5 narrows {@code img-src} against.
     *
     * <p>Without {@code basic()}'s enforced {@code rel="nofollow"}: links get the one policy the shell also
     * applies, from {@link ExternalLinks}, instead of a second one of jsoup's (core#169).
     */
    private static final Safelist SHOW_NOTES = Safelist.basic().removeEnforcedAttribute("a", "rel");

    private final SiteConfigService siteConfig;
    private final FeedService feeds;
    private final EpisodeQueryService episodes;
    private final LegalService legal;
    private final SiteUrls urls;
    private final ObjectMapper json = new ObjectMapper();

    public OgResolver(SiteConfigService siteConfig, FeedService feeds, EpisodeQueryService episodes,
                      LegalService legal, SiteUrls urls) {
        this.siteConfig = siteConfig;
        this.feeds = feeds;
        this.episodes = episodes;
        this.legal = legal;
        this.urls = urls;
    }

    /**
     * The site root, optionally filtered — the "All" tab (§6.1).
     *
     * @param season the season filter from the query string, or null
     * @param tag    the tag filter from the query string, or null
     * @param order  the ordering from the query string, or null
     * @return the view for this URL; never {@code null}
     */
    public PageView home(String locale, String season, String tag, String order) {
        String siteName = siteName();
        String query = SiteUrls.canonicalQuery(canonicalLang(locale), season, tag, order);
        String title = seasonSuffix(siteName, season);

        List<EpisodeSummary> listed = episodes
                .listSite(null, season(season), blankToNull(tag), !"oldest".equalsIgnoreCase(order),
                        PageRequest.of(0, NO_JS_EPISODES))
                .getContent();

        return new PageView(
                new IndexHtmlService.Meta(title, "", siteImageUrl()),
                urls.absolute("/", query),
                podcastSeries(siteName, null, urls.absolute("/")),
                episodeListHtml(title, listed));
    }

    /**
     * One feed tab, optionally filtered (§6.1).
     *
     * @param slugOrId the feed's public slug (a UUID still resolves, for older links)
     * @param season   the season filter from the query string, or null
     * @param tag      the tag filter from the query string, or null
     * @param order    the ordering from the query string, or null
     * @return the view for this URL; never {@code null}
     * @throws NotFoundException if no public feed is addressed by {@code slugOrId}
     */
    public PageView feed(String locale, String slugOrId, String season, String tag, String order) {
        FeedDetailView feed = feeds.detail(slugOrId);
        String path = "/feeds/" + feed.slug();
        String query = SiteUrls.canonicalQuery(canonicalLang(locale), season, tag, order);
        String title = seasonSuffix(feed.title(), season);
        String description = plainText(feed.description());

        UUID feedId = feed.id();
        List<EpisodeSummary> listed = episodes
                .listSite(feedId, season(season), blankToNull(tag), !"oldest".equalsIgnoreCase(order),
                        PageRequest.of(0, NO_JS_EPISODES))
                .getContent();

        return new PageView(
                new IndexHtmlService.Meta(title, description,
                        feed.imageUrl() == null ? siteImageUrl() : feed.imageUrl()),
                urls.absolute(path, query),
                podcastSeries(feed.title(), description, urls.absolute(path)),
                episodeListHtml(title, listed));
    }

    /**
     * One episode's detail page (§6.2), optionally at a shared position (§6.4).
     *
     * <p>The timestamp does not change what this page <em>is</em>: the canonical URL, the JSON-LD and the
     * no-JS block are the episode's, whether or not someone linked to a moment inside it. It changes only
     * the URL advertised as {@code og:url}, so a card in a messenger points back at the moment that was
     * shared rather than at the top of the episode. An unparsable value is dropped
     * ({@link TimestampParam}), which is also what keeps the parameter from reaching the page as text.
     *
     * @param slug the episode's public slug
     * @param t    the raw {@code t} query parameter, or {@code null} when absent
     * @return the view for this URL; never {@code null}
     * @throws NotFoundException if no visible episode has that slug
     */
    public PageView episode(String locale, String slug, String t) {
        EpisodeDetail episode = episodes.detailBySlug(slug);
        String path = "/episodes/" + episode.slug();
        String canonical = urls.absolute(path, SiteUrls.canonicalQuery(canonicalLang(locale), null, null, null));
        java.util.OptionalInt at = TimestampParam.parse(t);
        String shareUrl = at.isPresent() ? urls.absolute(path, TimestampParam.query(at.getAsInt())) : canonical;

        return new PageView(
                new IndexHtmlService.Meta(
                        episode.title() == null ? siteName() : episode.title(),
                        plainText(episode.description()),
                        episode.imageUrl() == null ? siteImageUrl() : episode.imageUrl(),
                        // One episode is a piece of dated content, not a website — and `article` is the type
                        // that carries a publication date a scraper will show.
                        "article",
                        episode.audioUrl(),
                        audioMime(episode.audioUrl()),
                        episode.publishedAt()),
                canonical,
                shareUrl,
                podcastEpisode(episode, canonical),
                episodeHtml(episode));
    }

    /**
     * The MIME type of an enclosure, as far as its URL admits.
     *
     * <p>The feed's declared enclosure type is not carried on the display snapshot (§4.2), so this reads the
     * extension and answers only for the formats where that is unambiguous. {@code og:audio:type} is
     * optional — a client that gets no type still gets the URL — so guessing is the one thing not worth
     * doing here.
     *
     * @param audioUrl the audio URL, possibly null
     * @return the MIME type, or {@code null} when it cannot be told
     */
    private static String audioMime(String audioUrl) {
        if (audioUrl == null) {
            return null;
        }
        // Enclosure URLs routinely carry tracking query strings; the extension is what precedes them.
        String path = audioUrl.split("[?#]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (path.endsWith(".m4a") || path.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (path.endsWith(".ogg") || path.endsWith(".oga")) {
            return "audio/ogg";
        }
        if (path.endsWith(".wav")) {
            return "audio/wav";
        }
        if (path.endsWith(".opus")) {
            return "audio/opus";
        }
        return null;
    }

    /**
     * One legal page (§12.6). Its body is already sanitized by {@link LegalService}, so the no-JS block is
     * simply the page.
     *
     * @param slug   the page slug
     * @param locale the requested UI locale, or null for the site default
     * @return the view for this URL; never {@code null}
     * @throws NotFoundException if no page with that slug renders in the requested or default locale
     */
    public PageView legal(String slug, String locale) {
        String resolved = locale == null ? defaultLocale() : locale;
        RenderedPage page = legal.render(slug, resolved);
        return new PageView(
                new IndexHtmlService.Meta(page.title(), "", siteImageUrl()),
                urls.absolute("/legal/" + page.slug(),
                        SiteUrls.canonicalQuery(canonicalLang(resolved), null, null, null)),
                null,
                "<h1>" + IndexHtmlService.escape(page.title()) + "</h1>\n" + page.html());
    }

    // ---- structured data (§6.6) ----

    /** {@code PodcastSeries} for the site and feed views. */
    private String podcastSeries(String name, String description, String url) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", "https://schema.org");
        node.put("@type", "PodcastSeries");
        node.put("name", name);
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        }
        node.put("url", url);
        return write(node);
    }

    /** {@code PodcastEpisode} for the detail page, including its series and audio when there is one. */
    private String podcastEpisode(EpisodeDetail episode, String url) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", "https://schema.org");
        node.put("@type", "PodcastEpisode");
        node.put("name", episode.title());
        node.put("url", url);
        if (episode.description() != null && !episode.description().isBlank()) {
            node.put("description", plainText(episode.description()));
        }
        if (episode.publishedAt() != null) {
            node.put("datePublished", DateTimeFormatter.ISO_INSTANT.format(episode.publishedAt()));
        }
        if (episode.episodeNo() != null) {
            node.put("episodeNumber", episode.episodeNo());
        }
        if (episode.season() != null) {
            node.put("partOfSeason", ordered("@type", "PodcastSeason", "seasonNumber", episode.season()));
        }
        if (episode.imageUrl() != null && !episode.imageUrl().isBlank()) {
            node.put("image", episode.imageUrl());
        }
        if (episode.audioUrl() != null && !episode.audioUrl().isBlank()) {
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("@type", "AudioObject");
            audio.put("contentUrl", episode.audioUrl());
            if (episode.durationSeconds() != null) {
                audio.put("duration", Duration.ofSeconds(episode.durationSeconds()).toString());
            }
            node.put("associatedMedia", audio);
        }
        node.put("partOfSeries", ordered(
                "@type", "PodcastSeries", "name", siteName(), "url", urls.absolute("/")));
        return write(node);
    }

    /**
     * A nested JSON-LD node with its keys in the order given.
     *
     * <p>{@code Map.of} would do the job semantically — JSON objects are unordered — but it randomizes
     * iteration order per JVM run, which puts {@code @type} in a different place on every boot and makes the
     * served HTML differ byte-for-byte between two identical installs. Readable, diffable output is worth a
     * three-line helper.
     */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> node = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            node.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return node;
    }

    /**
     * Serializes a JSON-LD node.
     *
     * <p>Everything here is written by Jackson rather than concatenated, because these values are feed
     * content: a title containing a quote would otherwise produce invalid JSON, and one containing
     * {@code </script>} would end the block early and turn the rest of a podcaster's show notes into markup.
     * {@link IndexHtmlService} closes the second half of that by escaping the {@code <} of any {@code </}
     * on the way into the document.
     */
    private String write(Map<String, Object> node) {
        return json.writeValueAsString(node);
    }

    // ---- no-JS content (§6.6) ----

    /** The crawler-readable episode list for a site or feed view. */
    private String episodeListHtml(String heading, List<EpisodeSummary> listed) {
        StringBuilder html = new StringBuilder("<h1>").append(IndexHtmlService.escape(heading))
                .append("</h1>\n");
        if (listed.isEmpty()) {
            return html.toString();
        }
        html.append("<ul>\n");
        for (EpisodeSummary episode : listed) {
            if (episode.slug() == null || episode.slug().isBlank()) {
                continue;
            }
            html.append("  <li><a href=\"/episodes/").append(IndexHtmlService.escape(episode.slug()))
                    .append("\">").append(IndexHtmlService.escape(episode.title())).append("</a>");
            if (episode.publishedAt() != null) {
                html.append(" <time datetime=\"")
                        .append(DateTimeFormatter.ISO_INSTANT.format(episode.publishedAt()))
                        .append("\">")
                        .append(DateTimeFormatter.ISO_INSTANT.format(episode.publishedAt()))
                        .append("</time>");
            }
            if (episode.excerpt() != null && !episode.excerpt().isBlank()) {
                html.append("<p>").append(IndexHtmlService.escape(episode.excerpt())).append("</p>");
            }
            html.append("</li>\n");
        }
        return html.append("</ul>\n").toString();
    }

    /** The crawler-readable content of one episode: title, date and sanitized show notes. */
    private String episodeHtml(EpisodeDetail episode) {
        StringBuilder html = new StringBuilder("<h1>")
                .append(IndexHtmlService.escape(episode.title())).append("</h1>\n");
        if (episode.publishedAt() != null) {
            String stamp = DateTimeFormatter.ISO_INSTANT.format(episode.publishedAt());
            html.append("<time datetime=\"").append(stamp).append("\">").append(stamp).append("</time>\n");
        }
        if (episode.description() != null && !episode.description().isBlank()) {
            // Feed HTML, sanitized — never trusted, and never a route for an arbitrary-origin request.
            html.append(ExternalLinks.mark(Jsoup.clean(episode.description(), SHOW_NOTES), urls.base()))
                    .append('\n');
        }
        return html.toString();
    }

    // ---- helpers ----

    private String siteName() {
        String name = siteConfig.get().getSiteName();
        return name == null || name.isBlank() ? "Mosaicast" : name;
    }

    /**
     * The {@code lang} a canonical URL should carry for this view, or {@code null} when it should carry none.
     *
     * <p>The site default is deliberately dropped, exactly as {@code order=newest} is in
     * {@link SiteUrls#canonicalQuery}: a parameter whose value is what its absence already means would make
     * one view canonicalise two ways, and doubling every URL on the site is the cost that made the August
     * decision defer locale URLs in the first place. Carrying it only for a non-default language keeps the
     * addition to one extra URL per translated view.
     */
    private String canonicalLang(String locale) {
        return locale == null || locale.isBlank() || locale.equalsIgnoreCase(defaultLocale()) ? null : locale;
    }

    private String defaultLocale() {
        SiteConfig config = siteConfig.get();
        return config.getDefaultLocale() == null ? LegalService.DEFAULT_LOCALE : config.getDefaultLocale();
    }

    /** The site logo as an absolute URL, or null when the install has not uploaded one. */
    private String siteImageUrl() {
        return siteConfig.get().getLogoAssetId() == null
                ? null : urls.absolute("/branding/" + BrandingAsset.LOGO.key());
    }

    /** "Name – Season N" for a season-filtered view, per §6.4; the bare name otherwise. */
    private static String seasonSuffix(String name, String season) {
        Integer parsed = season(season);
        return parsed == null ? name : name + " – Season " + parsed;
    }

    private static Integer season(String season) {
        if (season == null || season.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(season.trim());
        } catch (NumberFormatException e) {
            // A junk season in a shared link is a filter that matches nothing, not a 404: the page still
            // exists, so it previews unfiltered rather than refusing.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Feed HTML reduced to a single line of text, for a meta description.
     *
     * <p>{@code Jsoup.parse(…).text()} rather than {@code Jsoup.clean(…, none())}: {@code clean} returns
     * <em>escaped</em> text, which would then be escaped a second time on the way into the attribute and
     * show a visitor {@code &amp;amp;} in a link preview.
     */
    private static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
        return text.length() <= DESCRIPTION_LIMIT
                ? text : text.substring(0, DESCRIPTION_LIMIT).trim() + "…";
    }
}
