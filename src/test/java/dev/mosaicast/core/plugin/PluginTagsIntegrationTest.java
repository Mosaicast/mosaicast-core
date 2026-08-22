// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeTagRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.core.tag.TagService;
import dev.mosaicast.core.tag.TagSource;
import java.net.URI;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The plugin-facing tag surface end to end (ARCHITECTURE §6.1, SDK 0.9.0 {@code ctx.tags}).
 *
 * <p>Two fixtures carry the two halves of the declaration: {@code tagger} declares
 * {@code writesEpisodes: true}, {@code tagreader} declares the block without it, and every other fixture
 * declares no block at all — which is the third case, and a 404 rather than a 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginTagsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    private EpisodeTagRepository episodeTags;

    @Autowired
    private TagService tags;

    /** Direct repository writes in a test need one, and the poll this simulates runs inside one. */
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transaction;

    private String slug;

    @BeforeEach
    void episodeCarryingAFeedTag() {
        slug = "tagcast-s01e01";
        if (refs.findBySlug(slug).isEmpty()) {
            Feed feed = feeds.save(Feed.rss("https://example.test/tags.xml", "Tag Cast"));
            refs.save(EpisodeRef.published(feed.getId(), "guid-tag-1", 1, 1, slug));
        }
        // What a poll would have written: the feed's own spelling, through the vocabulary.
        tags.tagEpisode(slug, "Maritime Lore", TagSource.FEED);
    }

    @Test
    void theVocabularyKeepsTheFirstSpellingAndCountsEpisodesSiteWide() {
        String body = rest.getForObject("/api/plugins/tagger/tags", String.class);

        // Canonical key for machines, the feed's spelling for people (§6.1).
        assertThat(body).contains("\"tag\":\"maritime lore\"").contains("\"label\":\"Maritime Lore\"");
        assertThat(body).contains("\"episodes\":1");
    }

    @Test
    void aPluginTagsItsOwnSubjectsAndReadsThemBack() {
        Session podcaster = devLogin("podcaster");

        // Any spelling on the way in; the canonical key on the way out.
        ResponseEntity<Void> written = rest.exchange(
                uri("/api/plugins/tagger/tags/MARITIME%20lore/subjects/page:kraken"),
                HttpMethod.PUT, podcaster.write(), Void.class);
        assertThat(written.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rest.getForObject(uri("/api/plugins/tagger/tags/maritime%20lore/subjects"), String.class))
                .contains("page:kraken");
        assertThat(rest.getForObject("/api/plugins/tagger/subjects/page:kraken/tags", String.class))
                .contains("maritime lore");

        // Idempotent, and the subject count is the calling plugin's own — a plugin must not learn the size
        // of a store it cannot read.
        rest.exchange(uri("/api/plugins/tagger/tags/maritime%20lore/subjects/page:kraken"),
                HttpMethod.PUT, podcaster.write(), Void.class);
        assertThat(rest.getForObject("/api/plugins/tagger/tags", String.class)).contains("\"subjects\":1");
        assertThat(rest.getForObject("/api/plugins/tagreader/tags", String.class)).contains("\"subjects\":0");

        ResponseEntity<Void> removed = rest.exchange(
                uri("/api/plugins/tagger/tags/maritime%20lore/subjects/page:kraken"),
                HttpMethod.DELETE, podcaster.delete(), Void.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForObject(uri("/api/plugins/tagger/tags/maritime%20lore/subjects"), String.class))
                .doesNotContain("page:kraken");
        // Dropping the last assignment never drops the word: the vocabulary is the site's.
        assertThat(rest.getForObject("/api/plugins/tagger/tags", String.class)).contains("maritime lore");
    }

    @Test
    void aPluginsEpisodeTagIsItsOwnRowAndSurvivesTheFeedsOverwrite() {
        Session podcaster = devLogin("podcaster");
        rest.exchange("/api/plugins/tagger/tags/Kraken/episodes/" + slug,
                HttpMethod.PUT, podcaster.write(), Void.class);

        UUID refId = refs.findBySlug(slug).orElseThrow().getId();
        assertThat(episodeTags.findTags(refId)).contains("kraken", "maritime lore");

        // The next poll rewrites what the feed owns and nothing else — the whole point of the source column.
        transaction.executeWithoutResult(status ->
                episodeTags.deleteByEpisodeRefIdAndSource(refId, TagSource.FEED));
        assertThat(episodeTags.findTags(refId)).contains("kraken");

        // And a plugin removing "its" tag removes only its own row: put the feed's back on the same tag,
        // drop the plugin's, and the episode still carries it.
        tags.tagEpisode(slug, "kraken", TagSource.FEED);
        rest.exchange("/api/plugins/tagger/tags/kraken/episodes/" + slug,
                HttpMethod.DELETE, podcaster.delete(), Void.class);
        assertThat(episodeTags.findTags(refId)).contains("kraken");
    }

    @Test
    void taggingAnEpisodeNeedsTheDeclaredCapability() {
        Session podcaster = devLogin("podcaster");

        ResponseEntity<String> refused = rest.exchange(
                "/api/plugins/tagreader/tags/kraken/episodes/" + slug,
                HttpMethod.PUT, podcaster.write(), String.class);

        // A refusal with a reason, not a silently dropped write: the plugin declared the read half only.
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("writesEpisodes");

        // Its own subjects are still fair game — that is the other half of why there are two flags.
        assertThat(rest.exchange("/api/plugins/tagreader/tags/kraken/subjects/note:1",
                HttpMethod.PUT, podcaster.write(), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aPluginWithNoTagsBlockHasNoSurfaceAtAll() {
        // 404, not 403: indistinguishable from an unknown plugin, the same answer the blob and schema
        // surfaces give a plugin that declared neither.
        assertThat(rest.getForEntity("/api/plugins/good/tags", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/plugins/nosuch/tags", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void readsAreFilteredToWhatTheCallerCouldSeeAnyway() {
        assertThat(rest.getForObject(uri("/api/plugins/tagger/tags/maritime%20lore/episodes"), String.class))
                .contains(slug);
        assertThat(rest.getForObject("/api/plugins/tagger/episodes/" + slug + "/tags", String.class))
                .contains("maritime lore");

        // An unknown tag is an empty answer, not an error — a plugin asking about a word nothing carries
        // has asked a reasonable question.
        assertThat(rest.getForObject("/api/plugins/tagger/tags/nothing-carries-this/episodes", String.class))
                .isEqualTo("[]");
    }

    @Test
    void aSubjectKeyWithASlashIsRefusedWithAReason() {
        Session podcaster = devLogin("podcaster");

        // The firewall would reject an encoded slash before any handler ran, so the host says so itself
        // rather than letting a plugin author meet a bodiless 400 from the network layer.
        ResponseEntity<String> refused = rest.exchange(
                "/api/plugins/tagger/tags/kraken/subjects/page%2Fkraken",
                HttpMethod.PUT, podcaster.write(), String.class);
        assertThat(refused.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
    }

    /**
     * The path as a {@link URI}, so it reaches the server as written.
     *
     * <p>{@code TestRestTemplate} treats a {@code String} url as a template and encodes it again, which turns
     * the {@code %20} in a canonical tag key into a literal {@code %2520} — a 400 that looks like the host
     * rejecting the request rather than the client mangling it.
     */
    private static URI uri(String path) {
        return URI.create(path);
    }

    private Session devLogin(String role) {
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
    }

    /** A logged-in session's cookies, with the CSRF header the write endpoints require. */
    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<Void> write() {
            return new HttpEntity<>(headers());
        }

        HttpEntity<Void> delete() {
            return new HttpEntity<>(headers());
        }

        private HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            return headers;
        }
    }
}
