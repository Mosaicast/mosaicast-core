// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The boot-time slug backfill against real Postgres (ARCHITECTURE §4.1).
 *
 * <p>These assert on the <strong>database</strong>, not on the in-memory entity, because that is exactly where
 * the bug they pin lived: {@code EpisodeRef.slug} was mapped {@code updatable = false}, so
 * {@code assignSlugIfAbsent} set the field, Hibernate omitted the column from the {@code UPDATE} it emitted,
 * and the backfill logged a success having written nothing. Every instance upgraded from a pre-V13 schema kept
 * {@code slug = NULL} on every boot — no public URL, no API lookup, no sitemap entry, invisible to plugins —
 * while the only signal anyone had said it had worked. A test that asserted on the entity would have passed
 * throughout.
 */
// RANDOM_PORT (not NONE): the app's SecurityConfig wires HttpSecurity, which needs a servlet web context.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class EpisodeSlugBackfillIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EpisodeSlugBackfill backfill;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private FeedRepository feeds;

    @Test
    void backfillPersistsSlugsForLegacyRows() {
        Feed feed = feeds.save(Feed.rss("https://example.test/legacy.xml", "The Sample Cast"));
        EpisodeRef legacy = refs.saveAndFlush(
                EpisodeRef.published(feed.getId(), "guid-legacy-1", 1, 6, null));
        assertThat(refs.findById(legacy.getId()).orElseThrow().getSlug()).isNull();

        backfill.run(null);

        // Re-read through a fresh query rather than the managed instance: the point is what reached the row.
        String persisted = refs.findAll().stream()
                .filter(ref -> ref.getId().equals(legacy.getId()))
                .findFirst()
                .orElseThrow()
                .getSlug();
        assertThat(persisted).isNotNull().isNotBlank();
        assertThat(refs.findVisibleBySlug(persisted)).isPresent();
    }

    @Test
    void backfillLeavesNoRowWithoutASlug() {
        Feed feed = feeds.save(Feed.rss("https://example.test/many.xml", "Another Cast"));
        for (int i = 0; i < 5; i++) {
            refs.save(EpisodeRef.published(feed.getId(), "guid-many-" + i, 1, i, null));
        }
        refs.flush();

        backfill.run(null);

        assertThat(refs.countBySlugIsNull()).isZero();
    }

    @Test
    void backfillNeverChangesASlugThatAlreadyExists() {
        // The identifier is immutable once minted — a re-run must not re-slug a row and orphan its links.
        Feed feed = feeds.save(Feed.rss("https://example.test/stable.xml", "Stable Cast"));
        refs.saveAndFlush(EpisodeRef.published(feed.getId(), "guid-stable", 2, 3, "already-minted-slug"));

        backfill.run(null);
        backfill.run(null);

        assertThat(refs.findVisibleBySlug("already-minted-slug")).isPresent();
    }
}
