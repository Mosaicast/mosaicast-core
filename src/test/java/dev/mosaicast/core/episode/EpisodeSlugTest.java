// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EpisodeSlug} — format, fallbacks, collision suffixing and slugify normalization. */
class EpisodeSlugTest {

    private static final java.util.function.Predicate<String> NONE_TAKEN = s -> false;

    @Test
    void feedPlusSeasonEpisode() {
        assertThat(EpisodeSlug.generate("The Sample Cast", 1, 6, "The Lighthouse Keeper", NONE_TAKEN))
                .isEqualTo("the-sample-cast-s01e06");
    }

    @Test
    void fallsBackToEpisodeNumberThenTitleThenEp() {
        assertThat(EpisodeSlug.generate("Cast", null, 6, "Title", NONE_TAKEN)).isEqualTo("cast-e6");
        assertThat(EpisodeSlug.generate("Cast", null, null, "A Grand Title!", NONE_TAKEN))
                .isEqualTo("cast-a-grand-title");
        assertThat(EpisodeSlug.generate("Cast", null, null, "  ", NONE_TAKEN)).isEqualTo("cast-ep");
    }

    @Test
    void appendsCounterOnCollision() {
        Set<String> taken = new HashSet<>(Set.of("the-cast-s01e01", "the-cast-s01e01-2"));
        assertThat(EpisodeSlug.generate("The Cast", 1, 1, "x", taken::contains))
                .isEqualTo("the-cast-s01e01-3");
    }

    @Test
    void slugifyStripsAccentsAndPunctuation() {
        assertThat(EpisodeSlug.slugify("Crème brûlée: Épisode #1!", 40)).isEqualTo("creme-brulee-episode-1");
        assertThat(EpisodeSlug.slugify("  ---trim--- ", 40)).isEqualTo("trim");
        assertThat(EpisodeSlug.slugify(null, 40)).isEmpty();
    }

    @Test
    void slugifyCapsLengthWithoutTrailingHyphen() {
        String slug = EpisodeSlug.slugify("a".repeat(30) + " " + "b".repeat(30), 40);
        assertThat(slug).hasSizeLessThanOrEqualTo(40).doesNotEndWith("-");
    }

    @Test
    void blankFeedTitleFallsBackToPodcast() {
        assertThat(EpisodeSlug.generate("", 2, 3, "t", NONE_TAKEN)).isEqualTo("podcast-s02e03");
    }
}
