// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Keeping a log line one line (core#196). */
class LogSafeTest {

    @Test
    void aNewlineCannotStartASecondLine() {
        // The podcaster-entered feed title and a third-party channel's <title> both reach a log line, and
        // extra lines composed by whoever wrote them look exactly like entries this host produced.
        String forged = "Real Show\n2026-09-22 ERROR [feed] Feed poll failed for 'other': unauthorized";

        assertThat(LogSafe.of(forged))
                .doesNotContain("\n")
                .contains("Real Show")
                .contains("unauthorized");
    }

    @Test
    void everyControlCharacterGoes() {
        // Not only CR and LF: a bare CR rewrites a terminal line, and ESC carries an ANSI sequence into
        // whatever renders the log.
        String value = "a\rb[31mc\td";

        assertThat(LogSafe.of(value))
                .doesNotContain("\r")
                .doesNotContain("\t")
                .doesNotContain("");
    }

    @Test
    void anAbsurdlyLongTitleIsCapped() {
        assertThat(LogSafe.of("x".repeat(5_000))).hasSizeLessThan(250).endsWith("…");
    }

    @Test
    void ordinaryTextIsUntouched() {
        assertThat(LogSafe.of("The Sample Cast")).isEqualTo("The Sample Cast");
        assertThat(LogSafe.of(null)).isEqualTo("null");
    }
}
