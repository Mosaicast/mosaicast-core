// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** The pure parts of serving and sizing a plugin file (core#183). */
class PluginBlobHeadersTest {

    @Test
    void anAsciiNameIsTheSimpleFormAlone() {
        assertThat(PluginBlobController.contentDisposition("diagram.png"))
                .isEqualTo("inline; filename=\"diagram.png\"");
        assertThat(PluginBlobController.contentDisposition(null)).isEqualTo("inline");
    }

    @Test
    void aNonAsciiNameTravelsAsUtf8InTheExtendedForm() {
        // Tomcat writes header values as ISO-8859-1, so the plain parameter alone downloaded as mojibake.
        String header = PluginBlobController.contentDisposition("Folge-Überblick.png");

        assertThat(header)
                .startsWith("inline; filename=\"Folge-_berblick.png\"; ")
                .contains("filename*=UTF-8''Folge-%C3%9Cberblick.png");
        // Every byte of the header is ASCII — nothing left for the container to re-encode.
        assertThat(header.chars().allMatch(c -> c < 0x80)).isTrue();
    }

    @Test
    void theExtendedFormRoundTripsWhatAttrCharCannotCarry() {
        String name = "Épisode 1 – notes (final).png";
        String header = PluginBlobController.contentDisposition(name);
        String encoded = header.substring(header.indexOf("UTF-8''") + "UTF-8''".length());

        // Space, parentheses and the en dash are outside attr-char, so all of them are escaped...
        assertThat(encoded).doesNotContain(" ", "(", ")", "–");
        // ...and decoding gives the original back exactly.
        assertThat(URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8)).isEqualTo(name);
    }

    @Test
    void theServletCeilingIsTheSmallerOfItsTwoLimits() {
        // A file is one part of a request, so a request limit below the file limit is the one that binds.
        assertThat(PluginBlobService.uploadCeiling(DataSize.ofMegabytes(12), DataSize.ofMegabytes(16)))
                .isEqualTo(DataSize.ofMegabytes(12).toBytes());
        assertThat(PluginBlobService.uploadCeiling(DataSize.ofMegabytes(12), DataSize.ofMegabytes(4)))
                .isEqualTo(DataSize.ofMegabytes(4).toBytes());
    }

    @Test
    void aNegativeLimitIsSpringForNone() {
        assertThat(PluginBlobService.uploadCeiling(DataSize.ofBytes(-1), DataSize.ofBytes(-1)))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(PluginBlobService.uploadCeiling(DataSize.ofBytes(-1), DataSize.ofMegabytes(16)))
                .isEqualTo(DataSize.ofMegabytes(16).toBytes());
    }
}
