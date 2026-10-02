// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The endpoint behind {@code ctx.feeds} (ARCHITECTURE §6.1/§7.5, SDK 0.9.0).
 *
 * <p>What is worth testing here is not that a snapshot comes back — it is the three properties the SDK
 * documents and plugins will rely on: the host filters, absence is not explained, and timestamps are ISO
 * strings rather than whatever the host's {@code ObjectMapper} happens to do with an {@code Instant}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginEpisodesEndpointIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    @BeforeEach
    void oneVisibleEpisodeAndOneWithdrawn() {
        if (refs.findBySlug("feedcast-s01e01").isPresent()) {
            return;
        }
        Feed feed = feeds.save(Feed.rss("https://example.test/feeds.xml", "Feed Cast"));
        EpisodeRef visible = refs.save(
                EpisodeRef.published(feed.getId(), "guid-feeds-1", 1, 1, "feedcast-s01e01"));
        displays.save(new EpisodeDisplay(visible.getId(), new DisplaySnapshot(
                "The Lighthouse", "<p>Notes.</p>", "https://cdn.test/1.mp3",
                Instant.parse("2026-02-03T10:00:00Z"), Duration.ofMinutes(42),
                "https://img.test/ep.jpg", "https://img.test/feed.jpg", "A Host", null)));

        EpisodeRef withdrawn = refs.save(
                EpisodeRef.published(feed.getId(), "guid-feeds-2", 1, 2, "feedcast-s01e02"));
        withdrawn.withdraw();
        refs.save(withdrawn);
    }

    @Test
    void answersWithSnapshotsKeyedBySlugAndIsoTimestamps() {
        String body = rest.getForObject(
                "/api/plugins/good/episodes?slugs=feedcast-s01e01", String.class);

        assertThat(body).contains("\"feedcast-s01e01\"").contains("\"title\":\"The Lighthouse\"");
        // The strings the SDK's mirrored DisplaySnapshot documents — not epoch seconds, which is what the
        // doc store's mapper would have written.
        assertThat(body).contains("\"publishedAt\":\"2026-02-03T10:00:00Z\"").contains("\"duration\":\"PT42M\"");
        // Plain text alongside the untrusted HTML (SDK 0.16.0) — derived on the way out, because the row was
        // stored in the pre-0.16 shape without it, as every row on an upgraded instance was.
        assertThat(body).contains("\"description\":\"<p>Notes.</p>\"").contains("\"descriptionText\":\"Notes.\"");
    }

    @Autowired
    private dev.mosaicast.core.episode.EpisodeQueryService episodeQueries;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void aPluginLearnsTheEpisodesFeedAndSeasonButTheSnapshotNeverStoresThem() {
        // SDK 0.17.0 (core#248): feed, season and episode number come from the identity layer, on the way out.
        Feed feed = Feed.rss("https://example.test/placed.xml", "Placed Cast");
        feed.assignSlugIfAbsent("placed-cast");
        feed = feeds.save(feed);
        // A season's prologue: itunes:season and no itunes:episode.
        EpisodeRef prologue =
                refs.save(EpisodeRef.published(feed.getId(), "guid-placed-0", 5, null, "placed-s05-prologue"));
        displays.save(new EpisodeDisplay(prologue.getId(), new DisplaySnapshot(
                "Prologue", "<p>Before.</p>", null, Instant.parse("2026-03-01T10:00:00Z"), Duration.ofMinutes(5),
                null, null, "A Host", null)));
        EpisodeRef numbered =
                refs.save(EpisodeRef.published(feed.getId(), "guid-placed-22", 5, 22, "placed-s05e22"));
        displays.save(new EpisodeDisplay(numbered.getId(), new DisplaySnapshot(
                "Twenty-two", "<p>On.</p>", null, Instant.parse("2026-03-08T10:00:00Z"), Duration.ofMinutes(40),
                null, null, "A Host", null)));

        String body = rest.getForObject("/api/plugins/good/episodes?slugs=placed-s05-prologue,placed-s05e22",
                String.class);
        assertThat(body).contains("\"feed\":\"placed-cast\"").contains("\"season\":5")
                .contains("\"episodeNo\":22");
        // Absent, not null, for the prologue's missing number: the view omits nulls.
        String prologueJson =
                body.substring(body.indexOf("\"placed-s05-prologue\""), body.indexOf("\"placed-s05e22\""));
        assertThat(prologueJson).contains("\"season\":5").doesNotContain("episodeNo");

        // The Java side (ctx.feeds().display) reads through the same service.
        DisplaySnapshot java = episodeQueries.displayForSlug("placed-s05e22");
        assertThat(java.feed()).isEqualTo("placed-cast");
        assertThat(java.season()).isEqualTo(5);
        assertThat(java.episodeNo()).isEqualTo(22);
        assertThat(java.seasonScope()).isEqualTo(dev.mosaicast.plugin.api.Scope.season("placed-cast", 5));

        // Nothing numbered at all — no season, no episode number: every placement field is simply absent,
        // and there is no season scope to build. Nothing about it is an error.
        EpisodeRef loose =
                refs.save(EpisodeRef.published(feed.getId(), "guid-placed-x", null, null, "placed-bonus"));
        displays.save(new EpisodeDisplay(loose.getId(), new DisplaySnapshot(
                "Bonus", "<p>Extra.</p>", null, Instant.parse("2026-03-09T10:00:00Z"), Duration.ofMinutes(3),
                null, null, "A Host", null)));
        String looseBody = rest.getForObject("/api/plugins/good/episodes?slugs=placed-bonus", String.class);
        assertThat(looseBody).contains("\"title\":\"Bonus\"").contains("\"feed\":\"placed-cast\"")
                .doesNotContain("season").doesNotContain("episodeNo");
        DisplaySnapshot looseJava = episodeQueries.displayForSlug("placed-bonus");
        assertThat(looseJava.season()).isNull();
        assertThat(looseJava.episodeNo()).isNull();
        assertThat(looseJava.seasonScope()).isNull();

        // And the stored rows carry none of it: placement belongs to the ref, not to the feed's snapshot.
        String stored = jdbc.queryForObject(
                "select snapshot::text from episode_display where episode_ref_id = ?", String.class,
                numbered.getId());
        assertThat(stored).doesNotContain("\"feed\"").doesNotContain("\"season\"").doesNotContain("episodeNo");
    }

    @Test
    void anEpisodeTheVisitorMayNotSeeIsAbsentRatherThanRedacted() {
        String body = rest.getForObject(
                "/api/plugins/good/episodes?slugs=feedcast-s01e01,feedcast-s01e02,never-existed",
                String.class);

        assertThat(body).contains("feedcast-s01e01");
        // A withdrawn episode and a slug nobody ever minted produce the same answer: nothing. Telling them
        // apart would confirm the existence of an episode this visitor was not shown.
        assertThat(body).doesNotContain("feedcast-s01e02").doesNotContain("never-existed");
    }

    @Test
    void anEmptyOrAbsentBatchIsAnEmptyAnswerNotAnError() {
        assertThat(rest.getForObject("/api/plugins/good/episodes", String.class)).isEqualTo("{}");
        assertThat(rest.getForObject("/api/plugins/good/episodes?slugs=", String.class)).isEqualTo("{}");
    }

    @Test
    void theNamespaceStillBelongsToThePluginItNames() {
        // The data is the host's, but /api/plugins/<id>/… is that plugin's namespace: an unknown or
        // switched-off plugin must not have one surface that keeps answering.
        assertThat(rest.getForEntity("/api/plugins/nosuch/episodes?slugs=feedcast-s01e01", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anOversizedBatchIsClampedRatherThanRejected() {
        StringBuilder slugs = new StringBuilder("feedcast-s01e01");
        for (int i = 0; i < PluginEpisodeController.MAX_SLUGS + 20; i++) {
            slugs.append(",filler-").append(i);
        }

        var response = rest.getForEntity("/api/plugins/good/episodes?slugs=" + slugs, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("feedcast-s01e01");
    }
}
