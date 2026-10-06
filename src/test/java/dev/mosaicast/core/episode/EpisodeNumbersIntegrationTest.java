// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.feed.RawEpisode;
import dev.mosaicast.core.feed.Reconciler;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Access;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A podcaster numbering an episode by hand (ARCHITECTURE §4.4, core#264), end to end: the prologue a feed
 * cannot call episode 0 becomes S5E0 for every reader — the shell, the season filter, plugins — and stays that
 * way across polls, until the podcaster hands it back to the feed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class EpisodeNumbersIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    @Autowired
    private Reconciler reconciler;

    private Feed feed;

    @BeforeEach
    void aFeed() {
        String slug = "numbered-" + UUID.randomUUID().toString().substring(0, 8);
        Feed fresh = Feed.rss("https://example.test/" + slug + ".xml", "Numbered Cast");
        fresh.assignSlugIfAbsent(slug);
        feed = feeds.save(fresh);
    }

    @Test
    void aPrologueNumberedZeroStaysZeroForEveryReaderAcrossPolls() {
        EpisodeRef prologue = imported("guid-prologue", "5.00 Prolog", 5, null);
        HttpHeaders podcaster = as("podcaster");

        JsonNode pinned = numbers(podcaster, HttpMethod.PUT, prologue.getSlug(), "{\"season\":5,\"episodeNo\":0}");
        assertThat(pinned.path("pinned").asBoolean()).isTrue();
        assertThat(pinned.path("episodeNo").asInt()).isZero();
        assertThat(pinned.path("feedSeason").asInt()).isEqualTo(5);
        assertThat(pinned.path("feedEpisodeNo").isNull()).as("the feed still says no number").isTrue();

        // A poll that changes the item: the snapshot follows the feed, the numbers stay the podcaster's.
        poll(raw("guid-prologue", "5.00 – Prolog", 5, null));
        EpisodeRef afterPoll = refs.findBySlug(prologue.getSlug()).orElseThrow();
        assertThat(afterPoll.getSeason()).isEqualTo(5);
        assertThat(afterPoll.getEpisodeNo()).isZero();

        // The feed starts numbering it after all: recorded, still not applied.
        poll(raw("guid-prologue", "5.00 – Prolog", 5, 1));
        afterPoll = refs.findBySlug(prologue.getSlug()).orElseThrow();
        assertThat(afterPoll.getEpisodeNo()).isZero();
        assertThat(afterPoll.getFeedEpisodeNo()).isEqualTo(1);

        // Every reader sees the pinned number: the public episode, the season filter, and a plugin.
        assertThat(JSON.readTree(rest.getForObject("/api/episodes/" + prologue.getSlug(), String.class))
                .path("episodeNo").asInt()).isZero();
        assertThat(rest.getForObject("/api/feeds/" + feed.getSlug() + "/episodes?season=5", String.class))
                .contains(prologue.getSlug());
        assertThat(rest.getForObject("/api/plugins/good/episodes?slugs=" + prologue.getSlug(), String.class))
                .contains("\"season\":5").contains("\"episodeNo\":0");
        // The slug was minted once and is kept: links shared before the change still work.
        assertThat(afterPoll.getSlug()).isEqualTo(prologue.getSlug());
    }

    @Test
    void handingTheNumbersBackToTheFeedTakesEffectAtOnce() {
        EpisodeRef bonus = imported("guid-bonus", "Special zu Staffel 4", 4, 42);
        HttpHeaders podcaster = as("podcaster");
        numbers(podcaster, HttpMethod.PUT, bonus.getSlug(), "{\"season\":4,\"episodeNo\":null}");
        assertThat(refs.findBySlug(bonus.getSlug()).orElseThrow().getEpisodeNo()).isNull();

        JsonNode back = numbers(podcaster, HttpMethod.DELETE, bonus.getSlug(), null);

        assertThat(back.path("pinned").asBoolean()).isFalse();
        assertThat(back.path("episodeNo").asInt()).isEqualTo(42);
        EpisodeRef stored = refs.findBySlug(bonus.getSlug()).orElseThrow();
        assertThat(stored.getEpisodeNo()).isEqualTo(42);
        // And the next poll moves it again, as for any episode.
        poll(raw("guid-bonus", "Special zu Staffel 4", 4, 43));
        assertThat(refs.findBySlug(bonus.getSlug()).orElseThrow().getEpisodeNo()).isEqualTo(43);
    }

    @Test
    void aPlannedEpisodeIsNumberedWithItsPlanAndANegativeNumberIsRefused() {
        EpisodeRef plan = refs.save(EpisodeRef.planned(feed.getId(), 5, 0,
                new DisplaySnapshot("Prolog", null, null, null, null, null, null, null, null),
                feed.getSlug() + "-plan"));
        HttpHeaders podcaster = as("podcaster");

        assertThat(call(podcaster, HttpMethod.PUT, plan.getSlug(), "{\"season\":5,\"episodeNo\":1}").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        EpisodeRef released = imported("guid-negative", "Episode", 1, 1);
        ResponseEntity<String> negative =
                call(podcaster, HttpMethod.PUT, released.getSlug(), "{\"season\":1,\"episodeNo\":-1}");
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negative.getBody()).contains("episode.numbers.negative");
        assertThat(refs.findBySlug(released.getSlug()).orElseThrow().isNumbersPinned()).isFalse();
    }

    @Test
    void aFanCannotNumberAnEpisode() {
        EpisodeRef episode = imported("guid-fan", "Episode", 1, 1);
        assertThat(call(as("fan"), HttpMethod.PUT, episode.getSlug(), "{\"season\":1,\"episodeNo\":0}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refs.findBySlug(episode.getSlug()).orElseThrow().isNumbersPinned()).isFalse();
    }

    @Test
    void matchingAPlanCarriesTheNumbersAPodcasterSetOnTheImportedEpisode() {
        HttpHeaders podcaster = as("podcaster");
        EpisodeRef plan = refs.save(EpisodeRef.planned(feed.getId(), 5, 0,
                new DisplaySnapshot("Prologue", null, null, null, null, null, null, null, null),
                feed.getSlug() + "-prologue-plan"));
        // The feed's item has no number, so the plan's S5E0 could never bind on its own.
        EpisodeRef imported = imported("guid-matched", "5.00 Prolog", 5, null);
        numbers(podcaster, HttpMethod.PUT, imported.getSlug(), "{\"season\":5,\"episodeNo\":0}");

        ResponseEntity<String> matched = rest.exchange("/api/admin/episodes/" + plan.getSlug() + "/match",
                HttpMethod.POST, new HttpEntity<>("{\"episode\":\"" + imported.getSlug() + "\"}", json(podcaster)),
                String.class);
        assertThat(matched.getStatusCode()).as(matched.getBody()).isEqualTo(HttpStatus.OK);

        EpisodeRef kept = refs.findBySlug(plan.getSlug()).orElseThrow();
        assertThat(kept.isNumbersPinned()).isTrue();
        assertThat(kept.getEpisodeNo()).isZero();
        assertThat(kept.getFeedEpisodeNo()).isNull();
    }

    // ---- helpers ----

    private EpisodeRef imported(String guid, String title, Integer season, Integer number) {
        EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), guid, season, number,
                feed.getSlug() + "-" + guid));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(title, "<p>Notes.</p>",
                "https://cdn.test/" + guid + ".mp3", Instant.parse("2026-01-20T05:00:00Z"), Duration.ofMinutes(100),
                null, null, "A Host", null)));
        return ref;
    }

    private void poll(RawEpisode... items) {
        reconciler.reconcile(feed.getId(), feed.getTitle(), List.of(items));
    }

    private static RawEpisode raw(String guid, String title, Integer season, Integer episode) {
        return new RawEpisode(guid, title, "<p>Notes.</p>", "https://cdn.test/" + guid + ".mp3",
                Instant.parse("2026-01-20T05:00:00Z"), season, episode, null,
                null, null, null, null, List.of(), Access.PUBLIC);
    }

    private HttpHeaders as(String role) {
        DevLogin.Cookies who = DevLogin.login(rest, role);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private static HttpHeaders json(HttpHeaders session) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> call(HttpHeaders session, HttpMethod method, String slug, String body) {
        return rest.exchange("/api/admin/episodes/" + slug + "/numbers", method,
                new HttpEntity<>(body, body == null ? session : json(session)), String.class);
    }

    private JsonNode numbers(HttpHeaders session, HttpMethod method, String slug, String body) {
        ResponseEntity<String> response = call(session, method, slug, body);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }
}
