// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import java.util.Locale;
import java.util.Optional;

/** Where a report stands (§20.3). {@code done} and {@code spam} close it: its contact goes, and later the report. */
public enum FeedbackStatus {
    NEW, SEEN, DONE, SPAM;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean closed() {
        return this == DONE || this == SPAM;
    }

    public static Optional<FeedbackStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (FeedbackStatus status : values()) {
            if (status.wire().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
