// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import java.util.Locale;
import java.util.Optional;

/**
 * Severity of an {@link AppLogEntry}, ordered most severe first so a viewer can filter "this level and above".
 * Deliberately a small closed set rather than SLF4J's full range: TRACE is noise for an operator, and the
 * viewer is an operator tool, not a debugger.
 */
public enum AppLogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG;

    /** Whether this level is at least as severe as {@code threshold} (ERROR is the most severe). */
    public boolean isAtLeast(AppLogLevel threshold) {
        return ordinal() <= threshold.ordinal();
    }

    /** Parses a level case-insensitively; empty when the value is null, blank or unknown. */
    public static Optional<AppLogLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
