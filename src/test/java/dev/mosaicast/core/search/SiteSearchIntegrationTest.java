// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Site-wide search end to end (ARCHITECTURE §6, SDK {@code SearchProvider}): core's episodes plus the
 * fixture plugin's own hits, in sections.
 *
 * <p>What is worth asserting is the host's three responsibilities — it resolves the URL and confines it to
 * the plugin's namespace, it passes the caller's role through for the plugin to filter on, and it bounds a
 * provider that will not finish.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class SiteSearchIntegrationTest {

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
    void anEpisodeToFind() {
        if (refs.findBySlug("searchcast-s01e01").isPresent()) {
            return;
        }
        Feed feed = feeds.save(Feed.rss("https://example.test/search.xml", "Search Cast"));
        EpisodeRef ref = refs.save(
                EpisodeRef.published(feed.getId(), "guid-search-1", 1, 1, "searchcast-s01e01"));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                "Kraken Watching", "<p>On very large squid.</p>", "https://cdn.test/1.mp3",
                Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(30), null, null, "A Host", null)));
    }

    @Test
    void answersInSectionsPerSourceRatherThanOneRankedList() {
        String body = rest.getForObject("/api/search?q=kraken", String.class);

        // Core's own hit and the plugin's, each in their own section — a plugin's score and ts_rank are not
        // on one scale, so merging them would produce an order nobody could explain.
        assertThat(body).contains("\"query\":\"kraken\"");
        assertThat(body).contains("Kraken Watching");
        assertThat(body).contains("\"pluginId\":\"good\"").contains("The Kraken");
    }

    @Test
    void pagesOfTiedRanksNeitherRepeatNorSkip() {
        // ts_rank produces long runs of identical scores over short show notes, and an ORDER BY that is not
        // a total order lets Postgres return tied rows in any order it likes per LIMIT/OFFSET — so the same
        // episode appears on page one *and* page two while another is never shown at all. Adding pagination
        // without a tiebreaker would have shipped that to visitors (core#178).
        //
        // Honest about its reach: this passes with and without the tiebreaker at this size, because
        // "unspecified" is not "adversarial" — a sequential scan over forty-five rows happens to come back
        // in the same order every time. What makes the ordering correct is that it is now total, which is a
        // property of the query rather than of one plan; this guards the *observable* half, so a future
        // change that reintroduces duplicates or skips is caught.
        Feed feed = feeds.save(Feed.rss("https://example.test/tied.xml", "Tied Cast"));
        for (int i = 0; i < 45; i++) {
            EpisodeRef ref = refs.save(EpisodeRef.published(
                    feed.getId(), "guid-tied-" + i, 1, i, "tiedcast-s01e" + i));
            // Identical text, so every row scores the same and only the tiebreaker separates them.
            displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                    "Narwhal", "<p>Narwhal.</p>", "https://cdn.test/t.mp3",
                    Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(30), null, null, "A Host",
                    null)));
        }

        List<String> seen = new ArrayList<>();
        int totalPages = -1;
        for (int page = 0; page < 5; page++) {
            SearchResults results = rest.getForObject("/api/search?q=narwhal&page=" + page,
                    SearchResults.class);
            totalPages = results.totalPages();
            results.episodes().forEach(episode -> seen.add(episode.slug()));
            if (page >= results.totalPages() - 1) {
                break;
            }
        }

        assertThat(totalPages).isGreaterThan(1);
        assertThat(seen).doesNotHaveDuplicates();
        // Every one of the forty-five, exactly once: no skips either, which is the other half of the same
        // defect and the one a single-page test cannot see.
        assertThat(seen).hasSize(45);
    }

    @Test
    void theAnswerSaysHowManyThereAreInTotal() {
        // A visitor could not tell "twenty results" from "the first twenty of hundreds": no total, no
        // "load more", nothing. Every other list on the site pages.
        SearchResults results = rest.getForObject("/api/search?q=kraken", SearchResults.class);

        assertThat(results.page()).isZero();
        assertThat(results.totalElements()).isPositive();
        assertThat(results.totalPages()).isPositive();
    }

    @Test
    void aHitCannotPointOutsideItsOwnPluginsNamespace() {
        String body = rest.getForObject("/api/search?q=kraken", String.class);

        // The fixture returns `../../admin/feeds` on purpose. The host owns URL shapes, so the subpath is
        // cleaned the way ctx.route.navigate cleans one — a search result must not become a way in
        // anywhere else.
        assertThat(body).contains("\"href\":\"/p/good/glossary/kraken\"");
        // The `..` segments are dropped rather than resolved, so the escape attempt lands harmlessly
        // inside the plugin's own subtree — never at the core route it was aiming for.
        assertThat(body).doesNotContain("\"href\":\"/admin/feeds\"");
        assertThat(body).contains("\"href\":\"/p/good/admin/feeds\"");
    }

    @Test
    void theCallersRoleReachesTheProviderThatHasToFilterOnIt() {
        // This is the one place in the contract where the host cannot resolve access — it has no model of a
        // plugin's objects. So what it must get right is passing the role through, and null for anonymous.
        assertThat(rest.getForObject("/api/search?q=kraken", String.class))
                .doesNotContain("Kraken (draft)");

        DevLogin.Cookies cookies = DevLogin.login(rest, "fan");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.session() + "; " + cookies.xsrf());
        String signedIn = rest.exchange("/api/search?q=kraken", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();

        assertThat(signedIn).contains("Kraken (draft)");
    }

    @Test
    void aProviderThatWillNotFinishCostsItsOwnSectionAndNothingElse() {
        // The fixture sleeps well past the budget for this query. The section comes back marked rather than
        // missing: "found nothing" and "did not answer" are different answers to the visitor.
        ResponseEntity<String> response = rest.getForEntity("/api/search?q=slow", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"pluginId\":\"good\"").contains("\"timedOut\":true");
    }

    @Test
    void anEmptyQueryMatchesNothingRatherThanEverything() {
        String body = rest.getForObject("/api/search?q=", String.class);

        assertThat(body).contains("\"episodes\":[]").contains("\"plugins\":[]");
    }
}
