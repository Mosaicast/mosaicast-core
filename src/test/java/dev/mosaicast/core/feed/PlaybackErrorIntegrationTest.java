// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A listener's browser telling the operator it could not play an episode (core#170).
 *
 * <p>The properties that make an anonymous write surface acceptable are what is pinned: anyone can report,
 * and however many do, one episode is one log line per quiet period.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class PlaybackErrorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    @BeforeEach
    void oneEpisodePerTest() {
        // One per slug, so the quiet period of one test cannot swallow another's line.
        for (String slug : new String[] {"deadcast-s01e01", "deadcast-s01e02", "deadcast-s01e03"}) {
            if (refs.findBySlug(slug).isPresent()) {
                continue;
            }
            Feed feed = feeds.findAll().stream().filter(f -> "Dead Cast".equals(f.getTitle())).findFirst()
                    .orElseGet(() -> feeds.save(Feed.rss("https://example.test/dead.xml", "Dead Cast")));
            int number = Integer.parseInt(slug.substring(slug.length() - 1));
            EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), "guid-dead-" + number, 1, number, slug));
            displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                    "Moved Enclosure " + number, "<p>Notes.</p>", "https://cdn.test/gone-" + number + ".mp3",
                    Instant.parse("2026-02-03T10:00:00Z"), Duration.ofMinutes(42), null, null, "A Host", null)));
        }
    }

    /** Anonymous, as most listeners are — with only the CSRF token every visitor's browser has. */
    private ResponseEntity<String> report(String slug, String body) {
        String csrf = DevLogin.token(rest);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);
        headers.add("X-XSRF-TOKEN", csrf);
        return rest.exchange("/api/episodes/" + slug + "/playback-error", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void anAnonymousListenersReportReachesTheFeedLog(CapturedOutput output) {
        ResponseEntity<String> response = report("deadcast-s01e01", "{\"code\":4}");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(output.getAll())
                .contains("could not play 'Moved Enclosure 1' from 'Dead Cast' (MEDIA_ERR_SRC_NOT_SUPPORTED)")
                .contains("the browser's report, not checked by this server");
    }

    @Test
    void manyReportsOfOneEpisodeAreOneLine(CapturedOutput output) {
        for (int i = 0; i < 5; i++) {
            assertThat(report("deadcast-s01e02", "{\"code\":2}").getStatusCode().value()).isEqualTo(204);
        }

        String line = "could not play 'Moved Enclosure 2'";
        assertThat(output.getAll().split(line, -1)).hasSize(2);
    }

    @Test
    void refusesACodeThatIsNotAMediaErrorAndAnEpisodeThatIsNotThere() {
        assertThat(report("deadcast-s01e03", "{\"code\":99}").getStatusCode().value()).isEqualTo(400);
        assertThat(report("no-such-episode", "{\"code\":4}").getStatusCode().value()).isEqualTo(404);
    }
}
