// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import java.util.Locale;
import java.util.Optional;

/** What a report is about (§20). Stored and sent lower-case. */
public enum FeedbackKind {
    PROBLEM, IDEA, OTHER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind a wire value names, or empty for anything else. */
    public static Optional<FeedbackKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (FeedbackKind kind : values()) {
            if (kind.wire().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
