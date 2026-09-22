// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The strict-mode media allow-list is <em>derived</em>, so what matters is that the derivation actually
 * reaches the two places media URLs live: the feed's own {@code image_url} column and the episode display
 * snapshot's JSONB, where artwork and audio have no column to map (§4.2).
 *
 * <p>A unit test cannot prove that — the query is native and the JSONB access is Postgres-specific — and
 * getting it wrong fails in the worst possible direction: an empty allow-list that silently blanks every
 * artwork tile on a site whose operator had just turned the setting on.
 */
// RANDOM_PORT (not NONE): SecurityConfig wires HttpSecurity, which needs a servlet web context.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class ExternalMediaHostRegistryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ExternalMediaHostRegistry registry;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    @Test
    void everyPlaceAMediaUrlCanLiveEndsUpInTheAllowList() {
        Feed feed = feeds.save(Feed.rss("https://feed.example.test/rss.xml", "Media Cast"));
        feed.updateChannelMeta("https://feedart.example.test/cover.png", "Host", "About");
        feeds.save(feed);

        EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), "guid-media-1", 1, 1, "media-s01e01"));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                "Episode", "notes", "https://audio.example.test/e1.mp3", Instant.now(),
                java.time.Duration.ofMinutes(30), "https://epart.example.test:8443/e1.jpg",
                "https://feedart.example.test/cover.png", "Host", "Subtitle")));

        registry.refresh();

        assertThat(registry.origins()).contains(
                "https://feedart.example.test",
                "https://audio.example.test",
                // The port is part of the origin — an allow-list that drops it allows the wrong thing.
                "https://epart.example.test:8443");
    }
}
