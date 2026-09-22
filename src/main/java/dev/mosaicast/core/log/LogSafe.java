// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

/**
 * Makes a value safe to interpolate into a log line (ARCHITECTURE §13).
 *
 * <p>A log line is one line. A value carrying a newline produces extra lines on stdout that look exactly
 * like genuine entries — an attacker-composed "Feed poll failed for …" is indistinguishable from one this
 * host wrote, in the stream an operator reads when something is wrong (core#196). The values that reach
 * these lines are the podcaster-entered feed title and the {@code <title>} of a third-party RSS channel,
 * neither of which this host controls.
 *
 * <p>The admin viewer is unaffected either way — its columns are structured and React renders them as
 * text — so this is about {@code stdout}, which is what a container deployment actually keeps.
 * {@code RobotsController} has done the same thing for the same reason since it was written.
 *
 * <p>Truncated as well as flattened: a title is a title, and a feed that sends a kilobyte of one should
 * not be able to push the rest of the line out of a log viewer's width.
 */
public final class LogSafe {

    /** Long enough for any real podcast title, short enough to keep a line readable. */
    private static final int MAX = 200;

    private LogSafe() {
    }

    /**
     * The value with its line breaks and control characters replaced, capped in length.
     *
     * @param value any untrusted text; {@code null} becomes the literal {@code "null"}, as interpolation
     *              would have produced anyway
     */
    public static String of(String value) {
        if (value == null) {
            return "null";
        }
        // Every C0 control, not just CR and LF: a bare CR rewrites a terminal line, and an ESC can carry an
        // ANSI sequence into whatever renders the log.
        String flat = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return flat.length() <= MAX ? flat : flat.substring(0, MAX) + "…";
    }
}
