// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code Range} parsing (§11). Every unusable header resolves to empty rather than an error, because the
 * caller's fallback — send the whole file with a 200 — is a valid answer to any range request, and a 416
 * for a header the server merely did not like breaks playback for no gain.
 */
class RangeHeaderTest {

    private static final long TOTAL = 1000;

    @Test
    void parsesTheThreeFormsThatAppearInPractice() {
        assertThat(RangeHeader.parse("bytes=0-499", TOTAL)).contains(new RangeHeader(0, 499));
        assertThat(RangeHeader.parse("bytes=500-", TOTAL)).contains(new RangeHeader(500, 999));
        // Suffix form: the last 500 bytes, which is how a player finds an MP3's trailing metadata.
        assertThat(RangeHeader.parse("bytes=-500", TOTAL)).contains(new RangeHeader(500, 999));
    }

    @Test
    void clampsAnEndPastTheResource() {
        // Common and harmless: a client asking for more than exists gets what exists.
        assertThat(RangeHeader.parse("bytes=900-99999", TOTAL)).contains(new RangeHeader(900, 999));
        assertThat(RangeHeader.parse("bytes=-99999", TOTAL)).contains(new RangeHeader(0, 999));
    }

    @Test
    void reportsLengthAndContentRange() {
        RangeHeader range = RangeHeader.parse("bytes=0-9", TOTAL).orElseThrow();

        assertThat(range.length()).isEqualTo(10);
        assertThat(range.contentRange(TOTAL)).isEqualTo("bytes 0-9/1000");
    }

    @Test
    void treatsMultipleRangesAsNoRange() {
        // Answering these properly needs multipart/byteranges, which nothing asks for. Whole file instead.
        assertThat(RangeHeader.parse("bytes=0-99,200-299", TOTAL)).isEmpty();
    }

    @Test
    void ignoresWhatItCannotUse() {
        assertThat(RangeHeader.parse(null, TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("items=0-9", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("bytes=", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("bytes=abc-def", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("bytes=500", TOTAL)).isEmpty();
        // Start past the end, an inverted range, and a zero-length suffix all name nothing.
        assertThat(RangeHeader.parse("bytes=1000-1500", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("bytes=500-200", TOTAL)).isEmpty();
        assertThat(RangeHeader.parse("bytes=-0", TOTAL)).isEmpty();
        // An empty resource has no byte to name.
        assertThat(RangeHeader.parse("bytes=0-0", 0)).isEmpty();
    }

    @Test
    void acceptsTheHeaderAsClientsActuallySendIt() {
        assertThat(RangeHeader.parse("BYTES=0-9", TOTAL)).contains(new RangeHeader(0, 9));
        assertThat(RangeHeader.parse("  bytes=0-9  ", TOTAL)).contains(new RangeHeader(0, 9));
        assertThat(RangeHeader.parse("bytes= 0-9 ", TOTAL)).contains(new RangeHeader(0, 9));
        // RFC 9110 allows no whitespace around the `=`, and nothing sends it — so this falls through to the
        // whole file rather than being guessed at.
        assertThat(RangeHeader.parse("bytes = 0-9", TOTAL)).isEmpty();
    }
}
