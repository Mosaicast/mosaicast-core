// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The v1 related strategy (ARCHITECTURE §6.3): pinned first, then season / shared tags / fuzzy title, with
 * the exclusions that keep a listener from being sent somewhere unplayable.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelatedEpisodesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    @Autowired
    private EpisodeTagRepository tags;

    @Autowired
    private dev.mosaicast.core.tag.TagService vocabulary;

    @Autowired
    private EpisodePinRepository pins;

    @Autowired
    private RelatedProvider related;

    @Autowired
    private EpisodePinService pinService;

    /** The episode everything is related to. */
    private UUID anchor;
    private UUID sameSeasonNeighbour;
    private UUID sameSeasonDistant;
    private UUID otherSeasonSharedTags;
    private UUID unrelated;
    private UUID plannedOne;
    private UUID withdrawnOne;
    private UUID inDisabledFeed;

    @BeforeAll
    void seed() {
        Feed feed = feeds.save(namedFeed("related-cast", "Related Cast", true));
        Feed disabled = feeds.save(namedFeed("off-cast", "Off Cast", false));

        anchor = episode(feed, 1, 5, "rel-s01e05", "The Lighthouse", List.of("fog", "sea"));
        sameSeasonNeighbour = episode(feed, 1, 6, "rel-s01e06", "The Harbour", List.of());
        sameSeasonDistant = episode(feed, 1, 40, "rel-s01e40", "Something Else Entirely", List.of());
        otherSeasonSharedTags = episode(feed, 3, 2, "rel-s03e02", "Deep Water", List.of("fog", "sea"));
        unrelated = episode(feed, 9, 1, "rel-s09e01", "Accounting Practices", List.of("tax"));
        inDisabledFeed = episode(disabled, 1, 1, "off-s01e01", "Hidden", List.of("fog", "sea"));

        plannedOne = planned(feed, 1, 7, "rel-s01e07", "Upcoming");
        withdrawnOne = episode(feed, 1, 4, "rel-s01e04", "Gone", List.of("fog"));
        refs.findById(withdrawnOne).ifPresent(ref -> {
            ref.withdraw();
            refs.save(ref);
        });

        related.invalidate();
    }

    @Test
    void aPinnedEpisodeComesFirstWhateverTheScoreSays() {
        // `unrelated` shares nothing and would never surface on merit — which is the point of a pin.
        pinService.pin("rel-s01e05", "rel-s09e01");
        try {
            List<UUID> result = related.related(anchor, 5);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0)).isEqualTo(unrelated);
        } finally {
            pinService.unpin("rel-s01e05", "rel-s09e01");
        }
    }

    @Test
    void theSameSeasonAndSharedTagsBeatAnUnrelatedEpisode() {
        List<UUID> result = related.related(anchor, 5);

        assertThat(result).contains(sameSeasonNeighbour, otherSeasonSharedTags);
        assertThat(result.indexOf(sameSeasonNeighbour)).isLessThan(result.indexOf(unrelated) < 0
                ? Integer.MAX_VALUE : result.indexOf(unrelated));
    }

    @Test
    void withinASeasonTheNearerEpisodeRanksHigher() {
        List<UUID> result = related.related(anchor, 10);

        assertThat(result).contains(sameSeasonNeighbour, sameSeasonDistant);
        assertThat(result.indexOf(sameSeasonNeighbour)).isLessThan(result.indexOf(sameSeasonDistant));
    }

    @Test
    void anEpisodeIsNeverRelatedToItself() {
        assertThat(related.related(anchor, 20)).doesNotContain(anchor);
    }

    @Test
    void plannedAndWithdrawnEpisodesAreNeverOffered() {
        List<UUID> result = related.related(anchor, 20);

        // PLANNED has no audio to play and WITHDRAWN is gone — both would be dead ends (§6.3).
        assertThat(result).doesNotContain(plannedOne, withdrawnOne);
    }

    @Test
    void anEpisodeInASwitchedOffFeedIsNeverOffered() {
        // It shares both tags with the anchor, so only the feed's state keeps it out.
        assertThat(related.related(anchor, 20)).doesNotContain(inDisabledFeed);
    }

    @Test
    void theLimitIsHonoured() {
        assertThat(related.related(anchor, 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void aPinToAnInvisibleEpisodeIsNotServedButIsStillListedForTheAdmin() {
        pinService.pin("rel-s01e05", "off-s01e01");
        try {
            // A pin records an intention, not a permission: the public read filters it…
            assertThat(related.related(anchor, 20)).doesNotContain(inDisabledFeed);
            // …while the admin still sees it, because they have to be able to unpin it.
            assertThat(pinService.list("rel-s01e05"))
                    .extracting(EpisodeSummary::slug)
                    .contains("off-s01e01");
        } finally {
            pinService.unpin("rel-s01e05", "off-s01e01");
        }
    }

    @Test
    void pinningIsInvalidatedImmediatelyRatherThanAtTheNextPoll() {
        assertThat(related.related(anchor, 5)).doesNotContain(unrelated);

        pinService.pin("rel-s01e05", "rel-s09e01");
        try {
            // No poll in between: a pin the curator cannot see take effect looks broken.
            assertThat(related.related(anchor, 5)).startsWith(unrelated);
        } finally {
            pinService.unpin("rel-s01e05", "rel-s09e01");
        }
        assertThat(related.related(anchor, 5)).doesNotContain(unrelated);
    }

    // ---- seeding helpers ----

    private static Feed namedFeed(String slug, String title, boolean enabled) {
        Feed feed = Feed.rss("https://example.test/" + slug + ".xml", title);
        feed.assignSlugIfAbsent(slug);
        feed.setEnabled(enabled);
        return feed;
    }

    private UUID episode(Feed feed, int season, int number, String slug, String title, List<String> tagList) {
        EpisodeRef ref = refs.save(EpisodeRef.published(
                feed.getId(), "guid-" + slug, season, number, slug));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                title, "<p>Notes.</p>", "https://cdn.test/" + slug + ".mp3",
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(number * 86_400L),
                Duration.ofMinutes(30), null, null, "A Host", null)));
        for (String tag : tagList) {
            // The vocabulary entry first: an assignment now references one, so writing the row alone would
            // fail the foreign key (§6.1). Written directly rather than through TagService.tagEpisode
            // because some of these fixtures are deliberately *not* visible — a switched-off feed is what
            // one of these tests is about, and the plugin-facing write refuses those by design.
            String key = vocabulary.ensure(tag);
            tags.save(new EpisodeTag(ref.getId(), key, dev.mosaicast.core.tag.TagSource.FEED));
        }
        return ref.getId();
    }

    private UUID planned(Feed feed, int season, int number, String slug, String title) {
        EpisodeRef ref = EpisodeRef.planned(feed.getId(), season, number,
                new DisplaySnapshot(title, null, null, null, null, null, null, null, null), slug);
        return refs.save(ref).getId();
    }
}
