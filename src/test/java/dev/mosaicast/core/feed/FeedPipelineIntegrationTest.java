// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.plugin.PluginDataKey;
import dev.mosaicast.core.plugin.PluginData;
import tools.jackson.databind.node.JsonNodeFactory;
import dev.mosaicast.core.plugin.PluginDataRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.episode.EpisodeSummary;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end feed pipeline against a real Postgres (Flyway schema) and a real HTTP feed
 * (ARCHITECTURE §5, §13.5): fetch → reconcile → query/search, conditional-GET 304, WITHDRAWN on
 * disappearance, and PLANNED binding by season/episode.
 */
@SpringBootTest
@Testcontainers
// The fixtures are served from a loopback HTTP server, which OutboundTargetPolicy refuses by default.
// Turning the check off here rather than special-casing loopback in the policy: the tests should exercise the
// same code path an operator gets, and the escape hatch is the supported way to reach a private target.
// Fixtures are served from loopback, so the egress filter has to be off — and it now takes both keys.
@TestPropertySource(properties = {
    "mosaicast.feed.allow-private-targets=true",
    "mosaicast.feed.allow-private-targets-confirmed=true"})
class FeedPipelineIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private FeedService feedService;

    @Autowired
    private EpisodeQueryService episodes;

    @Autowired
    private FeedRepository feedRepository;

    @Autowired
    private dev.mosaicast.core.episode.EpisodeRefRepository refRepository;

    @Autowired
    private dev.mosaicast.core.episode.EpisodeDisplayRepository displayRepository;

    @Autowired
    private BindingSuggestionRepository suggestionRepository;

    @Autowired
    private PluginDataRepository pluginData;

    @Autowired
    private dev.mosaicast.core.progress.ListeningProgressRepository progressRepository;

    @Autowired
    private dev.mosaicast.core.auth.AccountService accounts;

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>("v1");
    private String feedUrl;
    private String secondFeedUrl;

    private static String rss(String... items) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Test Cast</title>
                    %s
                  </channel>
                </rss>
                """.formatted(String.join("\n", items));
    }

    private static String item(String guid, String title, int season, int episode) {
        return """
                <item>
                  <title>%s</title>
                  <guid>%s</guid>
                  <description>Notes for %s</description>
                  <pubDate>Sat, 21 Jun 2026 00:00:00 GMT</pubDate>
                  <enclosure url="https://audio/%s.mp3" type="audio/mpeg" length="1000"/>
                  <itunes:season>%d</itunes:season>
                  <itunes:episode>%d</itunes:episode>
                  <itunes:duration>1:08:42</itunes:duration>
                </item>
                """.formatted(title, guid, guid, guid, season, episode);
    }

    @BeforeEach
    void startServer() throws IOException {
        // The Postgres container is shared across tests; start each from a clean slate.
        suggestionRepository.deleteAll();
        displayRepository.deleteAll();
        refRepository.deleteAll();
        feedRepository.deleteAll();

        body.set(rss(
                item("ep-12", "Why pigeons secretly hate us", 2, 12),
                item("ep-11", "The great coffee controversy", 2, 11)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.xml", exchange -> {
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (etag.get().equals(ifNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", etag.get());
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        // A second path serving the same body, for the cases that need two distinct feeds.
        server.createContext("/second.xml", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        feedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.xml";
        secondFeedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/second.xml";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void addFeed_reconcilesEpisodes_thenSearchAndSeasonsWork() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        var page = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(EpisodeSummary::title)
                .containsExactlyInAnyOrder("Why pigeons secretly hate us", "The great coffee controversy");
        // Runtime/date come from the feed snapshot (§4.2).
        assertThat(page.getContent()).allSatisfy(e -> {
            assertThat(e.status()).isEqualTo(EpisodeStatus.PUBLISHED);
            assertThat(e.durationSeconds()).isEqualTo(4122L); // 1:08:42
            assertThat(e.hasAudio()).isTrue();
        });

        assertThat(episodes.seasons(feed.id())).containsExactly(2);
        assertThat(episodes.search("pigeons", PageRequest.of(0, 20)).getContent())
                .extracting(EpisodeSummary::title).containsExactly("Why pigeons secretly hate us");
    }

    @Test
    void aFeedCanBeDeletedAndTakesWhatItBroughtWithIt() {
        // A feed could be disabled but never deleted — there was no DELETE on this surface at all, so a
        // feed added by typo, one whose URL was hijacked, and one pulling content that must come down were
        // all permanent (core#175). Disabling hid it; the rows stayed.
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        assertThat(refRepository.countByFeedId(feed.id())).isPositive();

        FeedService.DeletedFeed removed = feedService.delete(feed.id());

        assertThat(removed.episodes()).isPositive();
        assertThat(feedRepository.findById(feed.id())).isEmpty();
        // The refs go, and their snapshots, tags, listening progress and pins go with them by cascade.
        assertThat(refRepository.findByFeedId(feed.id())).isEmpty();
        assertThat(feedService.catalog()).isEmpty();
    }

    @Test
    void deletingAFeedTakesThePluginDocumentsItsScopesNamed() {
        // A plugin's store is keyed by the host's scope strings rather than by a foreign key, so nothing
        // cascades: without this, a deleted feed leaves partitions behind that nothing will ever reclaim.
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        String episodeSlug = refRepository.findByFeedId(feed.id()).getFirst().getSlug();
        // Written through the repository rather than the service: DataScope is package-private, and
        // widening it so a test in another package can name a scope would be the tail wagging the dog.
        pluginData.save(new PluginData(
                new PluginDataKey("good", "feed", feed.slug(), "note"),
                JsonNodeFactory.instance.objectNode()));
        pluginData.save(new PluginData(
                new PluginDataKey("good", "episode", episodeSlug, "note"),
                JsonNodeFactory.instance.objectNode()));

        FeedService.DeletedFeed removed = feedService.delete(feed.id());

        assertThat(removed.pluginDocuments()).isEqualTo(2);
        assertThat(pluginData.findAll())
                .noneMatch(d -> episodeSlug.equals(d.getId().getScopeId()))
                .noneMatch(d -> feed.slug().equals(d.getId().getScopeId()));
    }

    @Test
    void theSameFeedUrlCannotBeAddedTwice() {
        // Two rows meant two complete episode sets, and every episode appeared twice on the site: the GUID
        // uniqueness constraint is scoped to a feed, and findSiteVisibleIds has no cross-feed dedup
        // (core#184).
        feedService.createRss(feedUrl, "Test Cast");

        assertThatThrownBy(() -> feedService.createRss(feedUrl, "Test Cast Again"))
                .isInstanceOf(ConflictException.class);
        assertThat(feedService.catalog()).hasSize(1);
    }

    @Test
    void aBlankTitleTakesTheFeedsOwnChannelTitle() {
        // CreateFeed documents a blank title as "defaults to the feed's channel title"; the parsed title was
        // read only by preview(), so the feed was named after its host forever — and because the feed slug
        // and every episode slug prefix are minted from that name and are immutable, the mistake outlived
        // any later poll (core#184).
        FeedView feed = feedService.createRss(feedUrl, "  ");

        assertThat(feed.title()).isEqualTo("Test Cast");
        assertThat(feed.slug()).doesNotContain("127-0-0-1").doesNotContain("localhost");
    }

    @Test
    void aFeedGetsAReadableSlug_andItsOldUuidUrlKeepsWorking() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        // The public address is readable, like the episodes inside it.
        assertThat(feed.slug()).isEqualTo("test-cast");
        assertThat(feedService.detail("test-cast").title()).isEqualTo("Test Cast");

        // Feed URLs were UUID-shaped until this release, so anything shared before it must still resolve —
        // the id is not secret, and no slug can parse as a UUID.
        assertThat(feedService.detail(feed.id().toString()).slug()).isEqualTo("test-cast");
    }

    @Test
    void twoFeedsWithTheSameTitleGetDistinctSlugs() {
        FeedView first = feedService.createRss(feedUrl, "Test Cast");
        FeedView second = feedService.createRss(secondFeedUrl, "Test Cast");

        // The slug is the primary key of the public URL space; a collision would mean one feed shadowing
        // the other, so minting keeps counting until it finds a free one.
        assertThat(first.slug()).isEqualTo("test-cast");
        assertThat(second.slug()).isEqualTo("test-cast-2");
    }

    @Test
    void catalog_listsFeeds_asSlimPublicView() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        List<PublicFeedView> catalog = feedService.catalog();

        assertThat(catalog).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(feed.id());
            assertThat(entry.title()).isEqualTo("Test Cast");
            assertThat(entry.episodeCount()).isEqualTo(2); // both items reconciled on add
        });
    }

    @Test
    void richMetadata_coversAuthorSubtitleTags_parsedAndSurfaced() {
        // A feed with a channel cover + author, one item with its own art/author/subtitle/keywords and one
        // item with none (so it falls back to the feed cover).
        String feedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Rich Cast</title>
                    <description>A rich demo feed.</description>
                    <itunes:image href="https://img.example/feed.jpg"/>
                    <itunes:author>Feed Author</itunes:author>
                    <item>
                      <title>Fancy One</title>
                      <guid>rich-1</guid>
                      <description>Notes</description>
                      <enclosure url="https://audio/rich-1.mp3" type="audio/mpeg" length="1000"/>
                      <itunes:season>1</itunes:season>
                      <itunes:episode>1</itunes:episode>
                      <itunes:image href="https://img.example/ep1.jpg"/>
                      <itunes:author>Episode Author</itunes:author>
                      <itunes:subtitle>A subtitle</itunes:subtitle>
                      <itunes:keywords>alpha, beta, christmas</itunes:keywords>
                    </item>
                    <item>
                      <title>Plain One</title>
                      <guid>rich-2</guid>
                      <description>Notes</description>
                      <enclosure url="https://audio/rich-2.mp3" type="audio/mpeg" length="1000"/>
                      <itunes:season>1</itunes:season>
                      <itunes:episode>2</itunes:episode>
                    </item>
                  </channel>
                </rss>
                """;
        body.set(feedXml);
        FeedView feed = feedService.createRss(feedUrl, "Rich Cast");

        var byTitle = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent().stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeSummary::title, s -> s));
        // Item with its own metadata.
        assertThat(byTitle.get("Fancy One").imageUrl()).isEqualTo("https://img.example/ep1.jpg");
        assertThat(byTitle.get("Fancy One").author()).isEqualTo("Episode Author");
        assertThat(byTitle.get("Fancy One").subtitle()).isEqualTo("A subtitle");
        // Item with none → artwork falls back to the feed cover, author to the channel author (§4.2).
        assertThat(byTitle.get("Plain One").imageUrl()).isEqualTo("https://img.example/feed.jpg");
        assertThat(byTitle.get("Plain One").author()).isEqualTo("Feed Author");

        // Feed-level channel metadata is stored and served for the feed panel (§6.1).
        var detail = feedService.detail(feed.slug());
        assertThat(detail.imageUrl()).isEqualTo("https://img.example/feed.jpg");
        assertThat(detail.author()).isEqualTo("Feed Author");
        assertThat(detail.description()).isEqualTo("A rich demo feed.");
        assertThat(detail.episodeCount()).isEqualTo(2);

        // Tags parsed from itunes:keywords, exposed and filterable.
        assertThat(episodes.tags(feed.id()))
                .extracting(dev.mosaicast.core.tag.TagOption::tag)
                .contains("alpha", "beta", "christmas");
        var christmas = episodes.listSite(feed.id(), null, "christmas", true, PageRequest.of(0, 20));
        assertThat(christmas.getContent()).singleElement()
                .satisfies(e -> assertThat(e.title()).isEqualTo("Fancy One"));
    }

    @Test
    void listeningProgress_isStoredPerUser() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        UUID episodeId = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent().get(0).id();
        var user = accounts.resolveLogin(
                new dev.mosaicast.core.auth.IdentityClaim("dev", "progress-tester", null, false, "Tester", null),
                null);

        progressRepository.save(new dev.mosaicast.core.progress.ListeningProgress(user.getId(), episodeId, 42));

        assertThat(progressRepository.findByIdUserIdAndIdEpisodeRefIdIn(user.getId(), List.of(episodeId)))
                .singleElement()
                .satisfies(p -> assertThat(p.getPositionSeconds()).isEqualTo(42));
        // Another user sees nothing (per-user isolation).
        assertThat(progressRepository.findByIdUserIdAndIdEpisodeRefIdIn(UUID.randomUUID(), List.of(episodeId)))
                .isEmpty();
    }

    @Test
    void siteScopeList_returnsEpisodesAcrossFeeds() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        var page = episodes.listSite(null, null, null, true, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(EpisodeSummary::title)
                .containsExactlyInAnyOrder("Why pigeons secretly hate us", "The great coffee controversy");

        // Narrowed to the one feed yields the same set (single-feed site == unified feed).
        var scoped = episodes.listSite(feed.id(), null, null, true, PageRequest.of(0, 20));
        assertThat(scoped.getTotalElements()).isEqualTo(2);
    }

    @Test
    void adjacent_walksTheFeedInCanonicalOrder() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        // Canonical order is season then episode number → ep-11 (S2E11) precedes ep-12 (S2E12).
        var ordered = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent();
        UUID e11 = ordered.stream().filter(e -> e.episodeNo() == 11).findFirst().orElseThrow().id();
        UUID e12 = ordered.stream().filter(e -> e.episodeNo() == 12).findFirst().orElseThrow().id();

        var fromE11 = episodes.adjacent(e11);
        assertThat(fromE11.prev()).isNull();
        assertThat(fromE11.next()).isNotNull();
        assertThat(fromE11.next().id()).isEqualTo(e12);

        var fromE12 = episodes.adjacent(e12);
        assertThat(fromE12.prev()).isNotNull();
        assertThat(fromE12.prev().id()).isEqualTo(e11);
        assertThat(fromE12.next()).isNull();
    }

    @Test
    void datelessEpisodeZero_sortsAsSeriesStart_notLast() {
        // A season-1 "episode 0" trailer that ships without a <pubDate> (so its snapshot has no publishedAt),
        // plus two dated regular episodes. The site feed must treat the dateless item as the earliest point in
        // the series — first in oldest order (before episode 1), last in newest order — rather than dumping it
        // after the last episode (regression: publishedAt `nulls last` in both directions).
        String feedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Zero Cast</title>
                    <description>d</description>
                    <item>
                      <title>Trailer</title><guid>z0</guid><description>n</description>
                      <itunes:season>1</itunes:season><itunes:episode>0</itunes:episode>
                    </item>
                    <item>
                      <title>One</title><guid>z1</guid><description>n</description>
                      <pubDate>Mon, 08 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:season>1</itunes:season><itunes:episode>1</itunes:episode>
                    </item>
                    <item>
                      <title>Two</title><guid>z2</guid><description>n</description>
                      <pubDate>Mon, 15 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:season>1</itunes:season><itunes:episode>2</itunes:episode>
                    </item>
                  </channel>
                </rss>
                """;
        body.set(feedXml);
        FeedView feed = feedService.createRss(feedUrl, "Zero Cast");

        var oldest = episodes.listSite(feed.id(), null, null, false, PageRequest.of(0, 20)).getContent();
        assertThat(oldest).extracting(EpisodeSummary::episodeNo).containsExactly(0, 1, 2);

        var newest = episodes.listSite(feed.id(), null, null, true, PageRequest.of(0, 20)).getContent();
        assertThat(newest).extracting(EpisodeSummary::episodeNo).containsExactly(2, 1, 0);
    }

    @Test
    void adjacentNavigation_followsReleaseOrder_notEpisodeNumber() {
        // Prev/next follow release order (publishedAt), matching the browsable feed and independent of episode
        // numbers. A numberless episode (episodeNo null — e.g. an Acast item that omits itunes:episode) is
        // placed by its date, NOT coerced to "episode 0": the early trailer leads, but the late bonus (also
        // numberless) sorts by its date at the end rather than jumping to the front.
        String feedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Release Cast</title><description>d</description>
                    <item><title>Trailer</title><guid>r0</guid><description>n</description>
                      <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:episodeType>trailer</itunes:episodeType></item>
                    <item><title>One</title><guid>r1</guid><description>n</description>
                      <pubDate>Mon, 08 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:season>1</itunes:season><itunes:episode>1</itunes:episode></item>
                    <item><title>Two</title><guid>r2</guid><description>n</description>
                      <pubDate>Mon, 15 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:season>1</itunes:season><itunes:episode>2</itunes:episode></item>
                    <item><title>Bonus</title><guid>r3</guid><description>n</description>
                      <pubDate>Mon, 22 Jan 2024 10:00:00 +0000</pubDate>
                      <itunes:episodeType>bonus</itunes:episodeType></item>
                  </channel>
                </rss>
                """;
        body.set(feedXml);
        FeedView feed = feedService.createRss(feedUrl, "Release Cast");

        // Release order (oldest→newest): Trailer, One, Two, Bonus — the numberless Bonus is last by date.
        var oldest = episodes.listSite(feed.id(), null, null, false, PageRequest.of(0, 20)).getContent();
        assertThat(oldest).extracting(EpisodeSummary::title)
                .containsExactly("Trailer", "One", "Two", "Bonus");

        java.util.Map<String, UUID> id = oldest.stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeSummary::title, EpisodeSummary::id));

        assertThat(episodes.adjacent(id.get("Trailer")).prev()).isNull();
        assertThat(episodes.adjacent(id.get("Trailer")).next().id()).isEqualTo(id.get("One"));
        var fromTwo = episodes.adjacent(id.get("Two"));
        assertThat(fromTwo.prev().id()).isEqualTo(id.get("One"));
        assertThat(fromTwo.next().id()).isEqualTo(id.get("Bonus"));
        // The late numberless bonus ends the sequence — not treated as an "episode 0" at the front.
        assertThat(episodes.adjacent(id.get("Bonus")).next()).isNull();
    }

    @Test
    void plannedEpisode_isNotANavigationNeighbour() {
        // Upcoming (PLANNED) episodes lead the browsable listing (§6.1) but are not part of the navigation
        // sequence (§6.2): they have no audio, so the player must never auto-advance into one. Regression:
        // the nav order reused the listing query, which sorts PLANNED first — the oldest release then had an
        // unreleased episode as its "previous".
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        var ordered = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent();
        UUID e11 = ordered.stream().filter(e -> e.episodeNo() == 11).findFirst().orElseThrow().id();
        UUID e12 = ordered.stream().filter(e -> e.episodeNo() == 12).findFirst().orElseThrow().id();
        UUID planned = feedService.createPlannedEpisode(feed.id(), 2, 13, "The one about pigeons, again", "tbd");

        // The released sequence is unchanged and closed at both ends.
        assertThat(episodes.adjacent(e11).prev()).isNull();
        assertThat(episodes.adjacent(e11).next().id()).isEqualTo(e12);
        assertThat(episodes.adjacent(e12).next()).isNull();

        // The planned episode's own page links back to the latest release, and nowhere forward.
        var fromPlanned = episodes.adjacent(planned);
        assertThat(fromPlanned.prev().id()).isEqualTo(e12);
        assertThat(fromPlanned.next()).isNull();
    }

    @Test
    void secondPoll_unchangedFeed_isNotModified() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        PollOutcome outcome = feedService.refreshNow(feed.id());
        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.NOT_MODIFIED);
    }

    @Test
    void aNewEpisodeDoesNotRewriteTheOnesThatDidNotChange() {
        // One new item used to rewrite every item's snapshot and tags and report them all as "updated"
        // (core#195). This is the round trip the unit test cannot make: the stored snapshot, read back out of
        // JSONB, has to equal the one parsed from an unchanged item — timestamps and durations included — or
        // the dirty check silently never fires.
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        body.set(rss(
                item("ep-13", "A brand new one", 2, 13),
                item("ep-12", "Why pigeons secretly hate us", 2, 12),
                item("ep-11", "The great coffee controversy", 2, 11)));
        etag.set("v2");
        PollOutcome outcome = feedService.refreshNow(feed.id());

        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.RECONCILED);
        assertThat(outcome.result().created()).isEqualTo(1);
        assertThat(outcome.result().updated()).isZero();
    }

    @Test
    void itemRemovedFromFeed_isWithdrawnNotDeleted() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");

        // Feed now lists only ep-12; ep-11 vanished.
        body.set(rss(item("ep-12", "Why pigeons secretly hate us", 2, 12)));
        etag.set("v2");
        PollOutcome outcome = feedService.refreshNow(feed.id());

        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.RECONCILED);
        assertThat(outcome.result().withdrawn()).isEqualTo(1);
        // Withdrawn episode is excluded from the listing but still exists (not hard-deleted).
        var titles = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20))
                .map(EpisodeSummary::title).getContent();
        assertThat(titles).containsExactly("Why pigeons secretly hate us");
    }

    @Test
    void plannedEpisode_bindsToMatchingFeedItem() {
        // Add the feed, then plan an upcoming episode for it before its RSS item exists (§4.3).
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        UUID plannedId = feedService.createPlannedEpisode(feed.id(), 2, 99, "Year in review", "predictions open");
        assertThat(episodes.detail(plannedId).status()).isEqualTo(EpisodeStatus.PLANNED);

        // The RSS item for that season/episode now appears → refresh auto-binds it.
        body.set(rss(
                item("ep-12", "Why pigeons secretly hate us", 2, 12),
                item("ep-99", "The big year-in-review", 2, 99)));
        etag.set("v2");
        PollOutcome outcome = feedService.refreshNow(feed.id());
        assertThat(outcome.result().bound()).isEqualTo(1);

        // The planned ref is now PUBLISHED and carries the feed snapshot, not the provisional title.
        assertThat(episodes.detail(plannedId).status()).isEqualTo(EpisodeStatus.PUBLISHED);
        assertThat(episodes.detail(plannedId).title()).isEqualTo("The big year-in-review");

        // No duplicate episode was created for that item (bound, not created).
        List<EpisodeSummary> onFeed = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent();
        assertThat(onFeed).extracting(EpisodeSummary::title)
                .contains("The big year-in-review")
                .doesNotHaveDuplicates();
    }

    @Test
    void fuzzySuggestion_isProposed_thenConfirmBindsThePlannedEpisode() {
        // Start with only ep-12 present, then plan an episode whose season/episode WON'T match the item to
        // come (so it can't auto-bind), but whose title is identical (so it fuzzy-matches, §5.3).
        body.set(rss(item("ep-12", "Why pigeons secretly hate us", 2, 12)));
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        UUID plannedId = feedService.createPlannedEpisode(
                feed.id(), 5, 1, "The great coffee controversy", "notes");

        // A new item appears with a different season/episode → creates a PUBLISHED ref and proposes a fuzzy
        // binding to the planned episode (never auto-applied).
        body.set(rss(
                item("ep-12", "Why pigeons secretly hate us", 2, 12),
                item("ep-77", "The great coffee controversy", 2, 11)));
        etag.set("v2");
        PollOutcome outcome = feedService.refreshNow(feed.id());
        assertThat(outcome.result().created()).isEqualTo(1);
        assertThat(outcome.result().bound()).isEqualTo(0);

        List<SuggestionView> proposed = feedService.listSuggestions(feed.id());
        assertThat(proposed).hasSize(1);
        SuggestionView suggestion = proposed.get(0);
        assertThat(suggestion.plannedRefId()).isEqualTo(plannedId);
        assertThat(suggestion.rawTitle()).isEqualTo("The great coffee controversy");
        assertThat(suggestion.similarity()).isGreaterThanOrEqualTo(TitleSimilarity.DEFAULT_THRESHOLD);

        // Before confirming, the auto-created ref and the planned ref coexist (two entries, same title).
        assertThat(refRepository.countByFeedId(feed.id())).isEqualTo(3);

        feedService.confirmSuggestion(suggestion.id());

        // The planned episode is now PUBLISHED, carries the feed item's guid/snapshot, and the auto-created
        // duplicate is gone — the feed item now resolves to the (formerly planned) ref.
        assertThat(episodes.detail(plannedId).status()).isEqualTo(EpisodeStatus.PUBLISHED);
        assertThat(episodes.detail(plannedId).title()).isEqualTo("The great coffee controversy");
        assertThat(refRepository.findByFeedIdAndExternalGuid(feed.id(), "ep-77"))
                .get().extracting(dev.mosaicast.core.episode.EpisodeRef::getId).isEqualTo(plannedId);
        assertThat(refRepository.countByFeedId(feed.id())).isEqualTo(2);
        assertThat(feedService.listSuggestions(feed.id())).isEmpty();

        List<EpisodeSummary> onFeed = episodes.listByFeed(feed.id(), null, PageRequest.of(0, 20)).getContent();
        assertThat(onFeed).extracting(EpisodeSummary::title).doesNotHaveDuplicates();
    }

    @Test
    void pollIntervalIsClampedToTheAllowedRange() {
        UUID id = feedRepository.save(Feed.rss("http://example.invalid/feed.xml", "Clamp Cast")).getId();

        // Below the floor → clamped up to 5 minutes; above the ceiling → clamped down to 7 days.
        assertThat(feedService.setPollInterval(id, 1).pollIntervalSeconds())
                .isEqualTo(Duration.ofMinutes(5).toSeconds());
        assertThat(feedService.setPollInterval(id, Duration.ofDays(30).toSeconds()).pollIntervalSeconds())
                .isEqualTo(Duration.ofDays(7).toSeconds());
        // A value in range is kept as-is.
        assertThat(feedService.setPollInterval(id, 3600).pollIntervalSeconds()).isEqualTo(3600);
    }

    @Test
    void disablingAFeedHidesItAndItsEpisodesFromPublicReads() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        UUID id = feed.id();

        // Enabled: present in the catalog (tabs) and the unified feed; detail resolves.
        assertThat(feedService.catalog()).extracting(PublicFeedView::id).contains(id);
        assertThat(episodes.listSite(null, null, null, true, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        UUID episodeId = episodes.listByFeed(id, null, PageRequest.of(0, 20)).getContent().get(0).id();

        feedService.setEnabled(id, false);

        // Disabled: gone from the catalog, the unified feed, the per-feed list, seasons and search; and its
        // detail 404s — "disabled" hides the feed from the public site, not just polling.
        assertThat(feedService.catalog()).extracting(PublicFeedView::id).doesNotContain(id);
        assertThat(episodes.listSite(null, null, null, true, PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(episodes.listByFeed(id, null, PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThat(episodes.seasons(id)).isEmpty();
        assertThat(episodes.search("pigeons", PageRequest.of(0, 20)).getContent()).isEmpty();
        assertThatThrownBy(() -> feedService.detail(id.toString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> episodes.detail(episodeId)).isInstanceOf(NotFoundException.class);

        // Re-enabling restores everything — nothing was deleted.
        feedService.setEnabled(id, true);
        assertThat(episodes.listSite(null, null, null, true, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void aFeedFilterThatResolvesToNothingNarrowsTheResultInsteadOfFailingTheRequest() {
        // `feedId` on /api/episodes and /api/tags is an optional *filter*, not the resource being addressed.
        // Routing it through resolvePublicId — which throws NotFoundException — made both endpoints 404 in
        // their entirety the moment an admin disabled a feed somebody held a filtered link to, where they had
        // returned an empty page. The shell then renders its load-error banner in place of the empty state,
        // and any integration passing a feedId breaks outright.
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        assertThat(feedService.findPublicId(feed.slug())).contains(feed.id());

        feedService.setEnabled(feed.id(), false);

        // The filter now names nothing visible, and says so without throwing.
        assertThat(feedService.findPublicId(feed.slug())).isEmpty();
        assertThat(feedService.findPublicId("never-existed")).isEmpty();
        assertThat(feedService.findPublicId(UUID.randomUUID().toString())).isEmpty();

        // Addressing the feed itself is still a 404 — there the feed *is* the resource, and answering 200
        // with an empty page would claim it exists.
        assertThatThrownBy(() -> feedService.resolvePublicId(feed.slug()))
                .isInstanceOf(NotFoundException.class);
    }
}
