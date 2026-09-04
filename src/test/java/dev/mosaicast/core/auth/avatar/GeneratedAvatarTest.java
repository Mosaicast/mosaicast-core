// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The fallback avatar (ARCHITECTURE §8.7) — everybody's default and every failure's landing place. */
class GeneratedAvatarTest {

    private static final UUID ID = UUID.fromString("4f2a1b3c-0000-0000-0000-000000000000");

    private static String svg(UUID id, String name) {
        return new String(GeneratedAvatar.svgFor(id, name), StandardCharsets.UTF_8);
    }

    @Test
    void drawsTheInitialOverAColourDerivedFromTheUserId() {
        String out = svg(ID, "Ned Flanders");
        assertThat(out).startsWith("<svg").contains(">N</text>").contains("fill=\"#");
    }

    @Test
    void theColourSurvivesARenameBecauseItComesFromTheId() {
        // Keyed on the id, so someone who renames does not also change colour — the picture stays a
        // recognisable marker for the same person.
        String before = svg(ID, "Ned");
        String after = svg(ID, "Alex");
        assertThat(colourOf(before)).isEqualTo(colourOf(after));
        assertThat(before).isNotEqualTo(after); // the glyph does change
    }

    @Test
    void escapesTheInitialBecauseADisplayNameIsUserInput() {
        // A name may begin with anything at all, and this one is going into an XML document.
        assertThat(svg(ID, "<script>")).contains("&lt;").doesNotContain("<script>");
        assertThat(svg(ID, "&")).contains("&amp;");
    }

    @Test
    void takesAWholeCodepointSoAnEmojiNameDoesNotYieldHalfASurrogate() {
        String out = svg(ID, "😀 Party");
        assertThat(out).contains("😀");
    }

    @Test
    void aNamelessUserStillGetsAPicture() {
        assertThat(svg(ID, null)).contains(">?</text>");
        assertThat(svg(ID, "   ")).contains(">?</text>");
    }

    @Test
    void theEtagChangesWithTheNameOrTheCacheWouldKeepTheOldInitial() {
        assertThat(GeneratedAvatar.etagFor(ID, "Ned")).isNotEqualTo(GeneratedAvatar.etagFor(ID, "Alex"));
        assertThat(GeneratedAvatar.etagFor(ID, "Ned")).isEqualTo(GeneratedAvatar.etagFor(ID, "Ned"));
    }

    /** The `fill="#xxxxxx"` of the disc, which is what the id decides. */
    private static String colourOf(String svg) {
        int at = svg.indexOf("fill=\"#");
        return svg.substring(at, at + 13);
    }

    @Test
    void referencesNoExternalResource() {
        // A webfont or a remote asset would render blank under the CSP (§12.5) and inside a plugin. The
        // `xmlns` is a namespace identifier rather than a fetch, so the assertion is about what would
        // actually cause a request.
        assertThat(svg(ID, "Ned"))
                .doesNotContain("@import")
                .doesNotContain("href")
                .doesNotContain("<image")
                .doesNotContain("url(");
    }
}
