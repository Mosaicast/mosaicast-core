// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeSummary;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The labels plugins get in {@code ctx.episodeLabels}, one per numbering case. */
class ScopeEpisodesLabelTest {

    private static EpisodeSummary episode(Integer season, Integer number, String title) {
        return new EpisodeSummary(UUID.randomUUID(), "slug", UUID.randomUUID(), season, number, null, null, null,
                title, null, null, null, null, null, null, false);
    }

    @Test
    void labelsEveryNumberingCase() {
        assertThat(ScopeEpisodesController.label(episode(1, 6, "Pilot"))).isEqualTo("S01E06 · Pilot");
        assertThat(ScopeEpisodesController.label(episode(null, 6, "Pilot"))).isEqualTo("E6 · Pilot");
        assertThat(ScopeEpisodesController.label(episode(null, null, "Pilot"))).isEqualTo("Pilot");
    }

    @Test
    void aSeasonWithoutAnEpisodeNumberKeepsItsSeason() {
        // core#248: a season-5 prologue with itunes:season and no itunes:episode was labelled as if it
        // belonged to no season at all.
        assertThat(ScopeEpisodesController.label(episode(5, null, "Prologue"))).isEqualTo("S05 · Prologue");
    }
}
