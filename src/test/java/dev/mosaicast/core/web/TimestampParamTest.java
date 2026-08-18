// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code ?t=} grammar (§6.4). The accepted and rejected tables are the same ones
 * {@code frontend/src/util/timestamp.test.ts} asserts — the shell decides where playback lands and this
 * decides what {@code og:url} says, so a disagreement means a link previews as one moment and plays another.
 */
class TimestampParamTest {

    @ParameterizedTest
    @CsvSource({
            "754, 754",
            "0, 0",
            "754s, 754",
            "12:34, 754",
            "1:02:03, 3723",
            "0:05, 5",
            "90:00, 5400",
            "1h02m03s, 3723",
            "1h2m, 3720",
            "90m, 5400",
            "2h, 7200",
            "'  12:34  ', 754",
            "1H02M03S, 3723",
    })
    void readsEveryAcceptedForm(String raw, int expected) {
        assertThat(TimestampParam.parse(raw)).hasValue(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "abc", "-5", "12:60", "1:2:3", "12:34:56:78", "h", "m", "86401", "25h", "1e3",
            "<script>alert(1)</script>", "754; DROP TABLE blob", "99999999999999999999999",
    })
    void ignoresAnythingElse(String raw) {
        assertThat(TimestampParam.parse(raw)).isEmpty();
    }

    @Test
    void treatsAnAbsentParameterAsNoTimestamp() {
        assertThat(TimestampParam.parse(null)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void emitsTheCanonicalBareSecondsForm() {
        assertThat(TimestampParam.query(754)).isEqualTo("t=754");
        assertThat(TimestampParam.query(-3)).isEqualTo("t=0");
    }
}
