// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a link scraper and a JS-less crawler actually receive from the host's own routes
 * (ARCHITECTURE §6.4/§6.6). The BRIEF's Definition of Done says a shared episode link shows a correct
 * preview and an episode page carries {@code PodcastEpisode} JSON-LD plus readable no-JS content; none of
 * that is observable from the shell, only from the bytes the server returns — so it is asserted here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerRenderedPagesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Pinned so the canonical assertions test the configured base URL, not the random test port. */
    @DynamicPropertySource
    static void baseUrl(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.base-url", () -> "https://podcast.test");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    private static final String FEED_SLUG = "seo-cast";

    private final String feedSlug = FEED_SLUG;

    /**
     * Seeds once for the class. A per-method seed would fight the unique slug index — and a feed created
     * straight from {@link Feed#rss} has no slug at all (it is minted by the service or the boot backfill),
     * so the public URL has to be assigned here the same way.
     */
    @BeforeAll
    void seedAnEpisode() {
        Feed feed = Feed.rss("https://example.test/seo.xml", "SEO Cast");
        feed.assignSlugIfAbsent(FEED_SLUG);
        feed = feeds.save(feed);
        EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), "guid-seo-1", 2, 7, "seo-cast-s02e07"));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                "The Lighthouse Episode",
                "<p>We visit a <b>lighthouse</b> and talk about fog.</p><script>alert(1)</script>",
                "https://cdn.test/audio/7.mp3",
                Instant.parse("2026-03-04T10:00:00Z"),
                Duration.ofMinutes(42),
                "https://cdn.test/art/7.jpg",
                "https://cdn.test/art/show.jpg",
                "A Host",
                "Fog and light")));
    }

    @Test
    void aSharedEpisodeLinkCarriesItsOwnPreview() {
        String html = body("/episodes/seo-cast-s02e07");

        assertThat(html).contains("<meta property=\"og:title\" content=\"The Lighthouse Episode\" />");
        assertThat(html).contains("We visit a lighthouse and talk about fog.");
        assertThat(html).contains("<meta property=\"og:image\" content=\"https://cdn.test/art/7.jpg\" />");
        assertThat(html).contains(
                "<link rel=\"canonical\" href=\"https://podcast.test/episodes/seo-cast-s02e07\" />");
        // The tags a messenger renders its card from: an episode is dated content, and a client that knows
        // what to do with audio gets the enclosure.
        assertThat(html).contains("<meta property=\"og:type\" content=\"article\" />");
        assertThat(html).contains("<meta property=\"og:audio\" content=\"https://cdn.test/audio/7.mp3\" />");
        assertThat(html).contains("<meta property=\"og:audio:type\" content=\"audio/mpeg\" />");
        assertThat(html).contains("<meta property=\"article:published_time\" content=\"2026-03-04T10:00:00Z\" />");
        assertThat(html).contains("<meta property=\"og:image:alt\" content=\"The Lighthouse Episode\" />");
        assertThat(html).contains("<meta property=\"og:locale\" content=\"en_US\" />");
    }

    @Test
    void aTimestampedEpisodeLinkSharesTheMomentButCanonicalizesToTheEpisode() {
        String html = body("/episodes/seo-cast-s02e07?t=754");

        // The page is the episode — that is what a search engine should index (§6.4).
        assertThat(html).contains(
                "<link rel=\"canonical\" href=\"https://podcast.test/episodes/seo-cast-s02e07\" />");
        // ...but the thing that was shared is the moment, so a card links back to it rather than to the top.
        assertThat(html).contains(
                "<meta property=\"og:url\" content=\"https://podcast.test/episodes/seo-cast-s02e07?t=754\" />");
        // Everything else about the view is unchanged by a position inside it.
        assertThat(html).contains("<meta property=\"og:title\" content=\"The Lighthouse Episode\" />");
        assertThat(html).contains("\"@type\":\"PodcastEpisode\"");
    }

    @Test
    void anUnreadableTimestampIsDroppedRatherThanEchoed() {
        // A mangled `t` in a forwarded link still opens the episode, and no input string reaches the page:
        // only a value that survives parsing is re-serialized into og:url.
        String html = body("/episodes/seo-cast-s02e07?t=%3Cscript%3Ealert(1)%3C/script%3E");

        assertThat(html).contains(
                "<meta property=\"og:url\" content=\"https://podcast.test/episodes/seo-cast-s02e07\" />");
        assertThat(html).doesNotContain("alert(1)");
        assertThat(html).doesNotContain("?t=");
    }

    @Test
    void aTimestampIsNotPartOfTheCanonicalFormOfAFilteredView() {
        // §6.1's filter axes are canonical; anything else in the query string is dropped, `t` included.
        String html = body("/feeds/seo-cast?season=2&t=754");

        assertThat(html).contains("<link rel=\"canonical\" href=\"https://podcast.test/feeds/seo-cast?season=2\" />");
        assertThat(html).doesNotContain("t=754");
    }

    @Test
    void anEpisodePageCarriesPodcastEpisodeJsonLd() {
        String html = body("/episodes/seo-cast-s02e07");

        assertThat(html).contains("<script type=\"application/ld+json\">");
        assertThat(html).contains("\"@type\":\"PodcastEpisode\"");
        assertThat(html).contains("\"name\":\"The Lighthouse Episode\"");
        assertThat(html).contains("\"datePublished\":\"2026-03-04T10:00:00Z\"");
        assertThat(html).contains("\"episodeNumber\":7");
        assertThat(html).contains("\"seasonNumber\":2");
        assertThat(html).contains("\"contentUrl\":\"https://cdn.test/audio/7.mp3\"");
        assertThat(html).contains("\"duration\":\"PT42M\"");
    }

    @Test
    void anEpisodePageCarriesReadableContentForCrawlersThatRunNoJs() {
        String html = body("/episodes/seo-cast-s02e07");

        assertThat(html).contains("<h1>The Lighthouse Episode</h1>");
        assertThat(html).contains("<b>lighthouse</b>");
        // Show notes are third-party HTML: the structure survives sanitizing, the script does not.
        assertThat(html).doesNotContain("alert(1)");
        // Inside the mount point, so React clears it rather than the page rendering twice.
        assertThat(html).contains("<div id=\"root\">\n<h1>The Lighthouse Episode</h1>");
    }

    @Test
    void anUnknownEpisodeIsARealNotFound() {
        // §6.6 rules out soft-404s: a 200 here is how a site gets its own not-found pages indexed.
        ResponseEntity<String> response = rest.getForEntity("/episodes/no-such-episode", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The body is still the shell, so a human lands on the app's not-found view and can navigate on.
        assertThat(response.getBody()).contains("<div id=\"root\">");
    }

    @Test
    void anUnknownFeedIsARealNotFound() {
        assertThat(rest.getForEntity("/feeds/no-such-feed", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aFeedPageListsItsEpisodesAndDeclaresPodcastSeries() {
        String html = body("/feeds/" + feedSlug);

        assertThat(html).contains("\"@type\":\"PodcastSeries\"");
        assertThat(html).contains("<a href=\"/episodes/seo-cast-s02e07\">The Lighthouse Episode</a>");
        assertThat(html).contains("<link rel=\"canonical\" href=\"https://podcast.test/feeds/" + feedSlug);
    }

    @Test
    void aFilteredViewPreviewsAsThatFilterAndCanonicalizesToIt() {
        String html = body("/feeds/" + feedSlug + "?season=2&order=newest");

        // The season is part of the view's identity (§6.1), so it is part of its preview (§6.4)…
        assertThat(html).contains("Season 2");
        // …and the canonical form drops the default ordering rather than minting a second URL for it.
        assertThat(html).contains(
                "<link rel=\"canonical\" href=\"https://podcast.test/feeds/" + feedSlug + "?season=2\" />");
    }

    @Test
    void theSiteRootIsServedWithItsOwnMetadata() {
        String html = body("/");

        assertThat(html).contains("<meta property=\"og:title\"");
        assertThat(html).contains("\"@type\":\"PodcastSeries\"");
        assertThat(html).contains("<link rel=\"canonical\" href=\"https://podcast.test/\" />");
    }

    @Test
    void theCanonicalUrlIgnoresAForwardedHostHeader() {
        // The same hole that was closed in the sitemap: a self-referential statement about the site's
        // identity must not be settable by whoever is asking. See SiteUrls.
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Forwarded-Host", "evil.example");
        String html = rest.exchange("/episodes/seo-cast-s02e07", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class).getBody();

        assertThat(html).doesNotContain("evil.example");
        assertThat(html).contains("https://podcast.test/episodes/seo-cast-s02e07");
    }

    @Test
    void aPageAnnouncesTheLanguageItWasAskedFor() {
        // Both of these were fixed strings before: `lang` is baked into the built index.html and og:locale
        // was the install's default on every URL, so a German page told every crawler it was English.
        String german = body("/episodes/seo-cast-s02e07?lang=de");

        assertThat(german).contains("<html lang=\"de\"");
        assertThat(german).contains("property=\"og:locale\" content=\"de_DE\"");
        // And it names itself: without this the German rendering has no URL of its own and an hreflang
        // alternate would be pointing at a page that claims to be the English one.
        assertThat(german).contains("rel=\"canonical\" href=\"https://podcast.test/episodes/"
                + "seo-cast-s02e07?lang=de\"");
    }

    @Test
    void theDefaultLanguageLeavesNoTraceInTheCanonicalUrl() {
        // The cost that made locale URLs get deferred in the first place: if `lang` were carried always,
        // every page on the site would have two canonical forms instead of one.
        assertThat(body("/episodes/seo-cast-s02e07?lang=en"))
                .contains("rel=\"canonical\" href=\"https://podcast.test/episodes/seo-cast-s02e07\"")
                .contains("<html lang=\"en\"");
        assertThat(body("/episodes/seo-cast-s02e07"))
                .contains("rel=\"canonical\" href=\"https://podcast.test/episodes/seo-cast-s02e07\"");
    }

    @Test
    void anUnknownLanguageServesTheDefaultAndMintsNoUrl() {
        // A stale alternate, a typo, or a language an admin switched off. It must not 404 — and it must not
        // produce a second indexable URL by echoing whatever was in the query string.
        String html = body("/episodes/seo-cast-s02e07?lang=klingon");

        assertThat(html).contains("<html lang=\"en\"");
        assertThat(html).contains("rel=\"canonical\" href=\"https://podcast.test/episodes/seo-cast-s02e07\"");
        assertThat(html).doesNotContain("klingon");
    }

    @Test
    void theSitemapOffersEveryPageInEveryLanguageTheShellHas() {
        String xml = body("/sitemap.xml");

        assertThat(xml).contains("xmlns:xhtml=\"http://www.w3.org/1999/xhtml\"");
        assertThat(xml).contains("<xhtml:link rel=\"alternate\" hreflang=\"de\" "
                + "href=\"https://podcast.test/episodes/seo-cast-s02e07?lang=de\" />");
        // Self-referential and reciprocal, or a crawler will not believe the set.
        assertThat(xml).contains("<xhtml:link rel=\"alternate\" hreflang=\"en\" "
                + "href=\"https://podcast.test/episodes/seo-cast-s02e07\" />");
        // x-default is the bare URL: no `lang`, so the site's own default answers.
        assertThat(xml).contains("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" "
                + "href=\"https://podcast.test/episodes/seo-cast-s02e07\" />");
    }

    private String body(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
