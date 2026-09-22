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
