// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code ?t=} deep-link grammar (ARCHITECTURE §6.4) — a position inside one episode, expressed the ways
 * people actually paste it: bare seconds ({@code 754}), the clock a player shows ({@code 12:34},
 * {@code 1:02:03}), or the unit form other podcast apps emit ({@code 1h02m03s}, {@code 90m}).
 *
 * <p>This is the server half of {@code frontend/src/util/timestamp.ts}, and the two are held to the same
 * table of cases by their tests. They have to agree: the shell decides where playback lands, this decides
 * what the injected {@code og:url} says, and a link that previews as one moment and plays another is worse
 * than one that carries no timestamp at all.
 *
 * <p><strong>Unparsable input is dropped, never rejected.</strong> A mangled {@code t} in a forwarded link
 * should still open the episode — the parameter is an enhancement to a URL whose meaning is the path. That
 * also makes this the sanitizing boundary for the value: only a value that survives parsing is ever echoed
 * back into the page, and it is re-serialized from an {@code int} rather than passed through, so no input
 * string reaches the HTML on this route.
 */
public final class TimestampParam {

    /** The largest position a link may carry: 24 h. Longer is a typo or a probe, not an episode. */
    private static final int MAX_SECONDS = 86_400;

    private static final Pattern PLAIN = Pattern.compile("\\d+");
    private static final Pattern MMSS = Pattern.compile("(\\d{1,3}):([0-5]\\d)");
    private static final Pattern HHMMSS = Pattern.compile("(\\d{1,2}):([0-5]\\d):([0-5]\\d)");
    // Digit runs are bounded so no group can overflow the arithmetic below; the range check rejects the
    // large-but-parsable ones.
    private static final Pattern UNITS = Pattern.compile("(?:(\\d{1,6})h)?(?:(\\d{1,6})m)?(?:(\\d{1,6})s)?");

    private TimestampParam() {
    }

    /**
     * Reads a {@code t} parameter as a position in seconds.
     *
     * @param raw the parameter value as it arrived, or {@code null} when absent
     * @return the position in whole seconds, or empty when absent, unparsable or out of range
     */
    public static OptionalInt parse(String raw) {
        if (raw == null) {
            return OptionalInt.empty();
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }

        long seconds;
        Matcher hhmmss = HHMMSS.matcher(value);
        Matcher mmss = MMSS.matcher(value);
        Matcher units = UNITS.matcher(value);
        if (PLAIN.matcher(value).matches()) {
            try {
                seconds = Long.parseLong(value);
            } catch (NumberFormatException e) {
                // A run of digits too long for a long — out of range by any reading.
                return OptionalInt.empty();
            }
        } else if (hhmmss.matches()) {
            seconds = group(hhmmss, 1) * 3600L + group(hhmmss, 2) * 60L + group(hhmmss, 3);
        } else if (mmss.matches()) {
            seconds = group(mmss, 1) * 60L + group(mmss, 2);
        } else if (units.matches() && (units.group(1) != null || units.group(2) != null || units.group(3) != null)) {
            // The unit pattern is all-optional, so it also matches the empty string; `value` cannot be empty
            // here, but a bare "h" or "m" would otherwise read as zero.
            seconds = group(units, 1) * 3600L + group(units, 2) * 60L + group(units, 3);
        } else {
            return OptionalInt.empty();
        }

        if (seconds < 0 || seconds > MAX_SECONDS) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) seconds);
    }

    /**
     * The canonical wire form of a position — always bare seconds, so two links to the same moment are the
     * same URL however the sharer typed the time.
     *
     * @param seconds a position in seconds
     * @return the query string {@code t=<seconds>}, without a leading {@code ?}
     */
    public static String query(int seconds) {
        return "t=" + Math.max(0, seconds);
    }

    private static long group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0L : Long.parseLong(value);
    }
}
