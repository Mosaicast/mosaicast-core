// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.episode.EpisodeSummary;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end feed pipeline against a real Postgres (Flyway schema) and a real HTTP feed
 * (ARCHITECTURE §5, §13.5): fetch → reconcile → query/search, conditional-GET 304, WITHDRAWN on
 * disappearance, and PLANNED binding by season/episode.
 */
@SpringBootTest
@Testcontainers
class FeedPipelineIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    private dev.mosaicast.core.progress.ListeningProgressRepository progressRepository;

    @Autowired
    private dev.mosaicast.core.auth.AccountService accounts;

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>("v1");
    private String feedUrl;

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
        server.start();
        feedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed.xml";
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
        var detail = feedService.detail(feed.id());
        assertThat(detail.imageUrl()).isEqualTo("https://img.example/feed.jpg");
        assertThat(detail.author()).isEqualTo("Feed Author");
        assertThat(detail.description()).isEqualTo("A rich demo feed.");
        assertThat(detail.episodeCount()).isEqualTo(2);

        // Tags parsed from itunes:keywords, exposed and filterable.
        assertThat(episodes.tags(feed.id())).contains("alpha", "beta", "christmas");
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
    void secondPoll_unchangedFeed_isNotModified() {
        FeedView feed = feedService.createRss(feedUrl, "Test Cast");
        PollOutcome outcome = feedService.refreshNow(feed.id());
        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.NOT_MODIFIED);
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
}
