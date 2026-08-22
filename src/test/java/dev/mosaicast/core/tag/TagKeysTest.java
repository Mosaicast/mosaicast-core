// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The canonical tag key (ARCHITECTURE §6.1) — the rule that decides whether several writers converge on one
 * word or fragment the vocabulary between them.
 */
class TagKeysTest {

    @Test
    void everySpellingOfOneWordConvergesOnOneKey() {
        assertThat(TagKeys.canonical("Maritime")).isEqualTo("maritime");
        assertThat(TagKeys.canonical("  maritime  ")).isEqualTo("maritime");
        assertThat(TagKeys.canonical("MARITIME")).isEqualTo("maritime");
        assertThat(TagKeys.canonical("Maritime   Lore")).isEqualTo("maritime lore");
        assertThat(TagKeys.canonical("Maritime\tLore\n")).isEqualTo("maritime lore");
    }

    @Test
    void theLabelKeepsTheCasingAndTidiesTheRest() {
        // Normalising is about making writers converge, not about lower-casing what a visitor reads.
        assertThat(TagKeys.label("  Maritime   Lore ")).isEqualTo("Maritime Lore");
    }

    @Test
    void aValueCarryingNothingIsNotATag() {
        assertThat(TagKeys.canonical(null)).isEmpty();
        assertThat(TagKeys.canonical("   ")).isEmpty();
        assertThat(TagKeys.isUsable(" \t ")).isFalse();
        assertThat(TagKeys.isUsable("x")).isTrue();
    }

    @Test
    void aPastedParagraphIsTruncatedRatherThanStored() {
        String long_ = "a".repeat(TagKeys.MAX_LENGTH + 50);

        // A keywords field someone pasted an abstract into is still a row in a shared vocabulary; bounding
        // it keeps the filter list usable and the key short enough to travel in a URL.
        assertThat(TagKeys.canonical(long_)).hasSize(TagKeys.MAX_LENGTH);
        assertThat(TagKeys.label(long_)).hasSize(TagKeys.MAX_LENGTH);
    }
}
