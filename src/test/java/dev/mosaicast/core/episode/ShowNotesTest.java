// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import org.junit.jupiter.api.Test;

class ShowNotesTest {

    @Test
    void stripsTagsDecodesEntitiesAndCollapsesWhitespace() {
        assertThat(ShowNotes.plainText("<p>Tom &amp; <b>Jerry</b></p>\n\n<p>go  <a href=\"x\">sailing</a></p>"))
                .isEqualTo("Tom & Jerry go sailing");
        // What a regex got wrong (core#196): a `>` inside an attribute does not end the tag.
        assertThat(ShowNotes.plainText("<a title=\"a > b\">link</a>")).isEqualTo("link");
        assertThat(ShowNotes.plainText("<script>alert(1)</script>ok")).isEqualTo("ok");
    }

    @Test
    void absentInputIsEmptyNotNull() {
        assertThat(ShowNotes.plainText(null)).isEmpty();
        assertThat(ShowNotes.plainText("   ")).isEmpty();
    }

    @Test
    @SuppressWarnings("removal")
    void completesASnapshotStoredWithoutPlainText() {
        DisplaySnapshot old = new DisplaySnapshot("t", "<p>Deep &amp; dark</p>", null, null, null,
                null, null, null, null);
        assertThat(old.descriptionText()).isEmpty();
        assertThat(ShowNotes.complete(old).descriptionText()).isEqualTo("Deep & dark");
        assertThat(ShowNotes.complete(old).description()).isEqualTo(old.description());

        // One that has it is left alone, and so is one with nothing to derive from.
        DisplaySnapshot current = new DisplaySnapshot("t", "<p>x</p>", null, null, null,
                null, null, null, null, "pinned");
        assertThat(ShowNotes.complete(current)).isSameAs(current);
        assertThat(ShowNotes.complete(null)).isNull();
    }
}
