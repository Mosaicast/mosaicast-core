// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What a file actually is, read from its leading bytes (§12.2, §11).
 *
 * <p>The load-bearing cases are the ones where the bytes and the claim disagree: that is the whole reason
 * this exists, and the assertion that {@code null} comes back for SVG is a security property rather than a
 * gap — every caller rejects an unrecognised format, so adding a case for SVG would be the only way to
 * store one.
 */
class MimeSnifferTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void recognisesTheRasterFormats() {
        assertThat(MimeSniffer.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png");
        assertThat(MimeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(MimeSniffer.sniff(ascii("GIF89a"))).isEqualTo("image/gif");
        assertThat(MimeSniffer.sniff(ascii("GIF87a"))).isEqualTo("image/gif");
        assertThat(MimeSniffer.sniff(bytes(0x00, 0x00, 0x01, 0x00))).isEqualTo("image/x-icon");
    }

    @Test
    void tellsTheTwoRiffContainersApart() {
        // RIFF alone says nothing: the format is at bytes 8-11, and answering "webp" for a WAV would hand a
        // caller an image content type for audio.
        assertThat(MimeSniffer.sniff(ascii("RIFF____WEBPVP8 "))).isEqualTo("image/webp");
        assertThat(MimeSniffer.sniff(ascii("RIFF____WAVEfmt "))).isEqualTo("audio/wav");
        assertThat(MimeSniffer.sniff(ascii("RIFF____AVI LIST"))).isNull();
    }

    @Test
    void readsTheIsoBaseMediaBrandRatherThanAssumingOne() {
        assertThat(MimeSniffer.sniff(ascii("____ftypavif____"))).isEqualTo("image/avif");
        assertThat(MimeSniffer.sniff(ascii("____ftypM4A ____"))).isEqualTo("audio/mp4");
        // The video brands are not answered as audio: a confident wrong answer is worse than none, because
        // the caller may go on to serve the file as what this said it was.
        assertThat(MimeSniffer.sniff(ascii("____ftypisom____"))).isNull();
        assertThat(MimeSniffer.sniff(ascii("____ftypmp42____"))).isNull();
    }

    @Test
    void recognisesTheAudioFormats() {
        assertThat(MimeSniffer.sniff(ascii("ID3"))).isEqualTo("audio/mpeg");
        assertThat(MimeSniffer.sniff(bytes(0xFF, 0xFB, 0x90, 0x00))).isEqualTo("audio/mpeg");
        assertThat(MimeSniffer.sniff(ascii("OggS"))).isEqualTo("audio/ogg");
    }

    @Test
    void refusesSvgHoweverItIsDressed() {
        // The case §12.2 exists for. An SVG is text, so it can be made to start almost any way short of a
        // real binary signature — and none of those is a case here.
        assertThat(MimeSniffer.sniff(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))).isNull();
        assertThat(MimeSniffer.sniff(ascii("<?xml version=\"1.0\"?><svg onload=\"alert(1)\"/>"))).isNull();
        assertThat(MimeSniffer.sniff(ascii("﻿<svg/>"))).isNull();
        assertThat(MimeSniffer.sniff(ascii("<!DOCTYPE html><html><script>"))).isNull();
    }

    @Test
    void survivesShortAndAbsentInput() {
        // A truncated upload matches fewer signatures rather than throwing — the caller's refusal path is
        // the same either way.
        assertThat(MimeSniffer.sniff(null)).isNull();
        assertThat(MimeSniffer.sniff(new byte[0])).isNull();
        assertThat(MimeSniffer.sniff(ascii("GIF"))).isNull();
        assertThat(MimeSniffer.sniff(ascii("RIFF"))).isNull();
        assertThat(MimeSniffer.sniff(bytes(0x89, 'P'))).isNull();
    }
}
