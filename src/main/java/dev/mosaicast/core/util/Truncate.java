// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.util;

/**
 * Cuts a string to a maximum length without splitting a character in half.
 *
 * <p>{@code substring(0, max)} counts UTF-16 code units, so a cut that lands between the two halves of a
 * surrogate pair — an emoji, a CJK extension character, anything outside the BMP — produces a lone
 * surrogate: half a character, which renders as a replacement glyph and, for a notification, is written
 * into a JSONB column that way (core#196). The codebase already knew better in one place:
 * {@code DisplayNames.length} counts codepoints explicitly.
 *
 * <p>The limits themselves are unchanged and still measured in code units, because that is what the
 * columns are sized in; what changes is that the cut lands on a character boundary at or below the limit.
 */
public final class Truncate {

    private Truncate() {
    }

    /**
     * {@code value}, at most {@code max} units long, ending on a whole character.
     *
     * @param value    the text; {@code null} passes through
     * @param max      the ceiling, in UTF-16 code units
     * @param ellipsis appended when something was cut — counted against {@code max}, so the result never
     *                 exceeds it
     */
    public static String toLength(String value, int max, String ellipsis) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int room = Math.max(0, max - ellipsis.length());
        return value.substring(0, boundaryAtOrBefore(value, room)) + ellipsis;
    }

    /** {@code value} at most {@code max} units long, with nothing appended. */
    public static String toLength(String value, int max) {
        return toLength(value, max, "");
    }

    /**
     * The largest index at or below {@code at} that does not sit inside a surrogate pair.
     *
     * <p>A high surrogate at {@code at - 1} means the cut would separate it from its low half, so the cut
     * moves back one. Only ever one: a pair is two units, so stepping back once is always enough.
     */
    private static int boundaryAtOrBefore(String value, int at) {
        if (at <= 0 || at >= value.length()) {
            return Math.max(0, Math.min(at, value.length()));
        }
        return Character.isHighSurrogate(value.charAt(at - 1)) ? at - 1 : at;
    }
}
