// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import java.util.Locale;

/**
 * Who may send feedback (§20.1). {@code EXTERNAL} points the footer link at the operator's own channel — a mail
 * address, a Discord, a tracker — and closes core's form, so nobody is made to run a second inbox.
 */
public enum FeedbackMode {
    OFF, SIGNED_IN, EVERYONE, EXTERNAL;

    /** The wire form, {@code signed-in} style. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static FeedbackMode parse(String value) {
        for (FeedbackMode mode : values()) {
            if (mode.wire().equals(value == null ? "" : value.trim().toLowerCase(Locale.ROOT))) {
                return mode;
            }
        }
        throw new IllegalArgumentException("feedback mode must be one of off, signed-in, everyone, external");
    }

    /** Whether core's own form takes reports at all in this mode. */
    public boolean acceptsReports() {
        return this == SIGNED_IN || this == EVERYONE;
    }
}
