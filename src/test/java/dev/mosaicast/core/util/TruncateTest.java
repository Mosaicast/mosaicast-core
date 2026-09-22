// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cutting a string without cutting a character in half (core#196). */
class TruncateTest {

    /** Two UTF-16 units. The cut has to land between characters, never inside one. */
    private static final String EMOJI = "😀";

    @Test
    void aShortEnoughValuePassesThrough() {
        assertThat(Truncate.toLength("hello", 10)).isEqualTo("hello");
        assertThat(Truncate.toLength("hello", 5)).isEqualTo("hello");
        assertThat(Truncate.toLength(null, 5)).isNull();
    }

    @Test
    void aCutInsideASurrogatePairMovesBack() {
        // "a😀b" is four units: a, high, low, b. Cutting at 2 would keep the high surrogate alone — half a
        // character, which renders as a replacement glyph and, for a notification, is stored that way.
        String value = "a" + EMOJI + "b";
        assertThat(value).hasSize(4);

        String cut = Truncate.toLength(value, 2);
        assertThat(cut).isEqualTo("a");
        assertThat(hasLoneSurrogate(cut)).isFalse();
    }

    @Test
    void aCutOnAPairBoundaryKeepsTheWholeCharacter() {
        assertThat(Truncate.toLength("a" + EMOJI + "b", 3)).isEqualTo("a" + EMOJI);
    }

    @Test
    void theEllipsisIsCountedAgainstTheLimit() {
        // The limit is what the column holds, so the result has to fit in it — ellipsis included.
        String cut = Truncate.toLength("abcdefghij", 5, "…");
        assertThat(cut).isEqualTo("abcd…").hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void anEllipsisAfterASurrogatePairStillLandsOnABoundary() {
        String cut = Truncate.toLength(EMOJI.repeat(5), 5, "…");

        assertThat(cut).isEqualTo(EMOJI + EMOJI + "…");
        assertThat(cut).hasSizeLessThanOrEqualTo(5);
        assertThat(hasLoneSurrogate(cut)).isFalse();
    }

    /** Whether any surrogate in the string is missing its partner — the defect this guards against. */
    private static boolean hasLoneSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
