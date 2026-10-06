// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.support.DevLogin;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Planning episodes end to end (ARCHITECTURE §4.3, core#252): from a script holding an access token — plan,
 * prepare plugin content on the returned slug while the episode is still quiet, retry safely — and from the
 * planning surface: edit, announce, cancel, and match to an episode the feed imported separately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PlannedEpisodeApiIntegrationTest {

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
    private JdbcTemplate jdbc;

    private Feed feed;

    @BeforeEach
    void aFeed() {
        String slug = "planned-" + UUID.randomUUID().toString().substring(0, 8);
        Feed fresh = Feed.rss("https://example.test/" + slug + ".xml", "Planned Cast");
        fresh.assignSlugIfAbsent(slug);
        feed = feeds.save(fresh);
    }

    // ---- helpers ----

    private String token() {
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        HttpHeaders headers = session(podcaster);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> minted = rest.exchange("/api/me/tokens", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"planner-" + UUID.randomUUID() + "\"}", headers), String.class);
        assertThat(minted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return JSON.readTree(minted.getBody()).path("secret").asString();
    }

    private static HttpHeaders session(DevLogin.Cookies who) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private ResponseEntity<String> call(String secret, HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secret);
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode plan(String secret, String body) {
        ResponseEntity<String> created = call(secret, HttpMethod.POST,
                "/api/admin/feeds/" + feed.getSlug() + "/planned-episodes", body);
        assertThat(created.getStatusCode().is2xxSuccessful()).as(created.getBody()).isTrue();
        return JSON.readTree(created.getBody());
    }

    // ---- tests ----

    @Test
    void aScriptPlansAQuietEpisodeAndPreparesPluginContentOnItsSlugStraightAway() {
        String secret = token();
        ResponseEntity<String> created = call(secret, HttpMethod.POST,
                "/api/admin/feeds/" + feed.getSlug() + "/planned-episodes",
                "{\"season\":3,\"episodeNo\":1,\"title\":\"Season three opener\",\"clientRef\":\"cms-301\"}");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode plan = JSON.readTree(created.getBody());
        String slug = plan.path("slug").asString();
        assertThat(slug).isNotBlank();
        assertThat(plan.path("phase").asString()).isEqualTo("PLANNED");
        assertThat(plan.path("url").asString()).isEqualTo("/episodes/" + slug);

        // The follow-up call: a bingo prepared on the episode while nobody else can see it.
        assertThat(call(secret, HttpMethod.PUT, "/api/plugins/good/data/episode/" + slug + "/bingo",
                "{\"cells\":9}").getStatusCode().is2xxSuccessful()).isTrue();

        // Quiet: to the public it does not exist yet.
        assertThat(rest.getForEntity("/api/episodes/" + slug, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/plugins/good/data/episode/" + slug + "/bingo", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A retry after a timeout returns the plan it already made, not a second one.
        ResponseEntity<String> retried = call(secret, HttpMethod.POST,
                "/api/admin/feeds/" + feed.getSlug() + "/planned-episodes",
                "{\"season\":3,\"episodeNo\":1,\"title\":\"Season three opener\",\"clientRef\":\"cms-301\"}");
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(retried.getBody()).path("slug").asString()).isEqualTo(slug);
    }

    @Test
    void aPodcasterPreviewsAQuietEpisodeWithoutAnyRequestFailingAndWithoutLeakingItsTitle() {
        String slug = plan(token(), "{\"title\":\"Secret guest reveal\"}").path("slug").asString();
        DevLogin.Cookies previewer = DevLogin.login(rest, "podcaster");
        HttpHeaders podcaster = session(previewer);
        podcaster.setAccept(List.of(MediaType.TEXT_HTML));

        // Everything the episode page loads answers the previewer...
        ResponseEntity<String> shell = rest.exchange("/episodes/" + slug, HttpMethod.GET,
                new HttpEntity<>(podcaster), String.class);
        assertThat(shell.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shell.getBody()).as("the site's metadata, not the episode's").doesNotContain("Secret guest reveal");
        for (String api : List.of("/api/episodes/" + slug, "/api/episodes/" + slug + "/adjacent",
                "/api/episodes/" + slug + "/related")) {
            assertThat(rest.exchange(api, HttpMethod.GET, new HttpEntity<>(session(previewer)), String.class)
                    .getStatusCode()).as(api).isEqualTo(HttpStatus.OK);
        }

        // ...and nobody else, the shell included.
        for (String path : List.of("/episodes/" + slug, "/api/episodes/" + slug,
                "/api/episodes/" + slug + "/adjacent", "/api/episodes/" + slug + "/related")) {
            HttpHeaders anonymous = new HttpHeaders();
            anonymous.setAccept(List.of(path.startsWith("/api") ? MediaType.APPLICATION_JSON : MediaType.TEXT_HTML));
            assertThat(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(anonymous), String.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void anEditAndAnAnnouncementChangeWhatThePublicSees() {
        String secret = token();
        String slug = plan(secret, "{\"title\":\"Working title\"}").path("slug").asString();

        JsonNode edited = JSON.readTree(call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug,
                "{\"title\":\"The real title\",\"season\":2,\"episodeNo\":5}").getBody());
        assertThat(edited.path("title").asString()).isEqualTo("The real title");
        assertThat(edited.path("slug").asString()).as("the slug stays: plugins hold it").isEqualTo(slug);

        // Scheduled in the future: still quiet.
        String later = Instant.now().plus(Duration.ofDays(3)).toString();
        assertThat(JSON.readTree(call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug,
                "{\"announceAt\":\"" + later + "\"}").getBody()).path("phase").asString()).isEqualTo("PLANNED");
        assertThat(rest.getForEntity("/api/episodes/" + slug, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Announced now: public, as upcoming.
        assertThat(JSON.readTree(call(secret, HttpMethod.POST, "/api/admin/episodes/" + slug + "/announce", null)
                .getBody()).path("phase").asString()).isEqualTo("UPCOMING");
        ResponseEntity<String> visible = rest.getForEntity("/api/episodes/" + slug, String.class);
        assertThat(visible.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visible.getBody()).contains("\"title\":\"The real title\"").contains("\"phase\":\"UPCOMING\"");

        // Back to quiet with an explicit null.
        call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug, "{\"announceAt\":null}");
        assertThat(rest.getForEntity("/api/episodes/" + slug, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Nonsense dates are refused with a code the admin UI can translate.
        ResponseEntity<String> bad = call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug,
                "{\"announceAt\":\"next tuesday\"}");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("planned.announceAt.invalid");
    }

    @Test
    void cancellingTakesThePluginContentPreparedForItAlong() {
        String secret = token();
        String slug = plan(secret, "{\"title\":\"Never to be\"}").path("slug").asString();
        call(secret, HttpMethod.PUT, "/api/plugins/good/data/episode/" + slug + "/bingo", "{\"cells\":9}");

        JsonNode cancelled = JSON.readTree(call(secret, HttpMethod.DELETE, "/api/admin/episodes/" + slug, null)
                .getBody());
        assertThat(cancelled.path("pluginDocuments").asInt()).isEqualTo(1);
        assertThat(refs.findBySlug(slug)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from plugin_data where scope_id = ?", Integer.class, slug))
                .isZero();
    }

    @Test
    void aReleasedEpisodeCannotBeEditedHere() {
        EpisodeRef released = importedEpisode("guid-released", "Already out", 1, 1);
        ResponseEntity<String> refused = call(token(), HttpMethod.PATCH,
                "/api/admin/episodes/" + released.getSlug(), "{\"title\":\"Rename\"}");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("planned.notPlanned");
    }

    @Test
    void matchingKeepsThePlansIdentityAndTakesOverTheImportedFeedItem() {
        String secret = token();
        JsonNode planJson = plan(secret, "{\"title\":\"The interview\",\"announceAt\":\"now\"}");
        String plannedSlug = planJson.path("slug").asString();
        call(secret, HttpMethod.PUT, "/api/plugins/good/data/episode/" + plannedSlug + "/bingo", "{\"cells\":9}");
        // The feed brought the same episode in under a title fuzzy matching did not catch.
        EpisodeRef imported = importedEpisode("guid-interview", "Ep. 40 — A conversation with Ada", 4, 40);
        UUID listener = jdbc.queryForObject("select id from app_user limit 1", UUID.class);
        jdbc.update("insert into listening_progress (user_id, episode_ref_id, position_seconds) values (?, ?, 300)",
                listener, imported.getId());

        JsonNode candidates = JSON.readTree(call(secret, HttpMethod.GET,
                "/api/admin/episodes/" + plannedSlug + "/match-candidates", null).getBody());
        assertThat(candidates.toString()).contains(imported.getSlug()).contains("\"hasPluginData\":false");

        ResponseEntity<String> matched = call(secret, HttpMethod.POST, "/api/admin/episodes/" + plannedSlug + "/match",
                "{\"episode\":\"" + imported.getSlug() + "\"}");
        assertThat(matched.getStatusCode()).as(matched.getBody()).isEqualTo(HttpStatus.OK);

        EpisodeRef kept = refs.findBySlug(plannedSlug).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(EpisodeStatus.PUBLISHED);
        assertThat(kept.getExternalGuid()).isEqualTo("guid-interview");
        assertThat(kept.getSeason()).isEqualTo(4);
        assertThat(refs.findBySlug(imported.getSlug())).as("the duplicate is gone").isEmpty();
        // The feed's data rules the display now; the plugin content stayed where it was prepared.
        assertThat(rest.getForEntity("/api/episodes/" + plannedSlug, String.class).getBody())
                .contains("A conversation with Ada");
        assertThat(rest.getForEntity("/api/plugins/good/data/episode/" + plannedSlug + "/bingo", String.class)
                .getBody()).contains("9");
        // And the listener's place moved with the episode.
        assertThat(jdbc.queryForObject("select position_seconds from listening_progress where episode_ref_id = ?",
                Integer.class, kept.getId())).isEqualTo(300);
    }

    @Test
    void aReleaseIsAnnouncedToPluginsAfterItCommitsWithThePhaseTheyRead() throws Exception {
        String secret = token();
        String plannedSlug = plan(secret, "{\"title\":\"Hook check\"}").path("slug").asString();
        // Planned and quiet: what the plugin sees through ctx.feeds as the podcaster preparing it.
        assertThat(call(secret, HttpMethod.GET, "/api/plugins/good/episodes?slugs=" + plannedSlug, null).getBody())
                .contains("\"phase\":\"planned\"");
        EpisodeRef imported = importedEpisode("guid-hook", "Hook check, released", 6, 1);

        call(secret, HttpMethod.POST, "/api/admin/episodes/" + plannedSlug + "/match",
                "{\"episode\":\"" + imported.getSlug() + "\"}");

        // The fixture plugin's onEpisodeReleased writes the slug into its own store — asynchronously, after
        // the commit, so wait for it rather than assume it.
        String released = null;
        for (int i = 0; i < 50 && released == null; i++) {
            String body = rest.getForObject("/api/plugins/good/data/site/main/released-last", String.class);
            if (body != null && body.contains(plannedSlug)) {
                released = body;
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(released).as("onEpisodeReleased was called with the plan's slug").contains(plannedSlug);
        assertThat(rest.getForObject("/api/plugins/good/episodes?slugs=" + plannedSlug, String.class))
                .contains("\"phase\":\"released\"");
    }

    @Test
    void aWriteThatMovesAPlansPhaseTellsPluginsAndOneThatDoesNotStaysQuiet() throws Exception {
        // core#270: the leak was an announced episode going quiet again with no plugin told.
        String secret = token();
        String slug = plan(secret, "{\"title\":\"Phase check\"}").path("slug").asString();

        call(secret, HttpMethod.POST, "/api/admin/episodes/" + slug + "/announce", null);
        assertThat(awaitPhase(slug, "upcoming")).isEqualTo("upcoming");

        call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug, "{\"announceAt\":\"2999-01-01T00:00:00Z\"}");
        assertThat(awaitPhase(slug, "planned")).isEqualTo("planned");

        // A title edit leaves the phase where it was: nothing to tell. A stray event would overwrite the
        // sentinel; the delivery that would do it lands well inside a second.
        call(secret, HttpMethod.PUT, "/api/plugins/good/data/site/main/phase:" + slug, "\"sentinel\"");
        call(secret, HttpMethod.PATCH, "/api/admin/episodes/" + slug, "{\"title\":\"Phase check, renamed\"}");
        Thread.sleep(1000);
        assertThat(awaitPhase(slug, "sentinel")).isEqualTo("sentinel");

        call(secret, HttpMethod.DELETE, "/api/admin/episodes/" + slug, null);
        assertThat(awaitPhase(slug, "gone")).isEqualTo("gone");
    }

    @Test
    void aReleaseRunsTheReleaseListenersFirstThenThePhaseOnes() throws Exception {
        String secret = token();
        String plannedSlug = plan(secret, "{\"title\":\"Order check\"}").path("slug").asString();
        EpisodeRef imported = importedEpisode("guid-order", "Order check, released", 7, 1);

        call(secret, HttpMethod.POST, "/api/admin/episodes/" + plannedSlug + "/match",
                "{\"episode\":\"" + imported.getSlug() + "\"}");

        assertThat(awaitPhase(plannedSlug, "released after " + plannedSlug))
                .isEqualTo("released after " + plannedSlug);
        // And the duplicate the match removed is gone for plugins too.
        assertThat(awaitPhase(imported.getSlug(), "gone")).isEqualTo("gone");
    }

    /** What the fixture's phase listener last wrote for this slug, waiting a while for a specific value. */
    private String awaitPhase(String slug, String expected) throws InterruptedException {
        String last = null;
        for (int i = 0; i < 50; i++) {
            String body = rest.getForObject("/api/plugins/good/data/site/main/phase:" + slug, String.class);
            last = body == null ? null : JSON.readTree(body).asString();
            if (expected.equals(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    @Test
    void aMatchOntoAnEpisodeThatAlreadyHasPluginDataIsRefusedAndChangesNothing() {
        String secret = token();
        String plannedSlug = plan(secret, "{\"title\":\"The quiz\"}").path("slug").asString();
        EpisodeRef imported = importedEpisode("guid-quiz", "Quiz night", 5, 2);
        // Fans already played a bingo on the imported one.
        assertThat(call(secret, HttpMethod.PUT, "/api/plugins/good/data/episode/" + imported.getSlug() + "/bingo",
                "{\"cells\":16}").getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> refused = call(secret, HttpMethod.POST, "/api/admin/episodes/" + plannedSlug + "/match",
                "{\"episode\":\"" + imported.getSlug() + "\"}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("planned.match.targetHasPluginData").contains("plugin data");
        assertThat(refs.findBySlug(plannedSlug).orElseThrow().getStatus()).isEqualTo(EpisodeStatus.PLANNED);
        assertThat(refs.findBySlug(imported.getSlug())).isPresent();
    }

    @Test
    void aPluginListsAQuietPlanForAPodcasterAndForNobodyElse() {
        // core#258: ctx.episodes comes from scope-episodes, which ignored the viewer.
        String secret = token();
        importedEpisode("guid-out", "Already out", 2, 0);
        String slug = plan(secret, "{\"season\":2,\"episodeNo\":1,\"title\":\"The Secret Season Opener\"}")
                .path("slug").asString();
        String site = "/api/plugins/scope-episodes?type=site&id=main";
        String feedScope = "/api/plugins/scope-episodes?type=feed&id=" + feed.getSlug();
        String season = "/api/plugins/scope-episodes?type=season&id=" + feed.getSlug() + ":2";
        String episode = "/api/plugins/scope-episodes?type=episode&id=" + slug;

        for (String path : List.of(site, feedScope, season, episode)) {
            JsonNode options = JSON.readTree(call(secret, HttpMethod.GET, path, null).getBody());
            assertThat(options.get(0).path("id").asString()).as(path).isEqualTo(slug);
            assertThat(options.get(0).path("label").asString()).as(path).contains("The Secret Season Opener");
            assertThat(rest.getForObject(path, String.class)).as("anonymous: " + path).doesNotContain(slug);
        }
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");
        assertThat(rest.exchange(feedScope, HttpMethod.GET, new HttpEntity<>(session(fan)), String.class).getBody())
                .doesNotContain(slug);
        // Only on the first page, so the public pages after it are what they were.
        assertThat(call(secret, HttpMethod.GET, feedScope + "&page=1&size=1", null).getBody()).doesNotContain(slug);
    }

    @Test
    void aFanCannotPlan() {
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");
        HttpHeaders headers = session(fan);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/api/admin/feeds/" + feed.getSlug() + "/planned-episodes", HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"x\"}", headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private EpisodeRef importedEpisode(String guid, String title, int season, int number) {
        EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), guid, season, number,
                feed.getSlug() + "-" + guid));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(title, "<p>Notes.</p>",
                "https://cdn.test/" + guid + ".mp3", Instant.parse("2026-05-01T10:00:00Z"), Duration.ofMinutes(30),
                null, null, "A Host", null)));
        return ref;
    }
}
