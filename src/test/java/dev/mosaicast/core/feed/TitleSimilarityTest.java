// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Risk-point unit tests for fuzzy title matching (ARCHITECTURE §5.3, §13.5). */
class TitleSimilarityTest {

    @Test
    void normalizeStripsAccentsPunctuationAndCase() {
        assertThat(TitleSimilarity.normalize("Ep. 12: Café — Pigeons!")).isEqualTo("ep 12 cafe pigeons");
        assertThat(TitleSimilarity.normalize(null)).isEmpty();
        assertThat(TitleSimilarity.normalize("   ")).isEmpty();
    }

    @Test
    void nearIdenticalTitlesMatch() {
        double similarity = TitleSimilarity.similarity(
                "The great coffee controversy", "The Great Coffee Controversy (Part 1)");
        assertThat(similarity).isGreaterThanOrEqualTo(TitleSimilarity.DEFAULT_THRESHOLD);
        assertThat(TitleSimilarity.matches(
                "The great coffee controversy", "The Great Coffee Controversy (Part 1)")).isTrue();
    }

    @Test
    void unrelatedTitlesDoNotMatch() {
        assertThat(TitleSimilarity.matches("Why pigeons hate us", "Nobody understands insurance")).isFalse();
    }

    @Test
    void blankTitleNeverMatches() {
        assertThat(TitleSimilarity.similarity("", "anything")).isZero();
        assertThat(TitleSimilarity.similarity("anything", null)).isZero();
    }
}
