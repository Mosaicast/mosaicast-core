// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Index names that fit Postgres's 63-byte limit without two becoming one (core#201). */
class PluginSchemaIndexNameTest {

    private static final String LONG = "p_wiki_" + "x".repeat(60);

    @Test
    void aNameThatFitsIsUnchanged() {
        assertThat(PluginSchemaMigrator.indexName("p_wiki_pages", "slug", "idx")).isEqualTo("p_wiki_pages_slug_idx");
    }

    @Test
    void twoTablesThatDifferOnlyPastTheCutStillGetTwoNames() {
        // The old truncation kept the field and cut the table, so these two came out identical and the second
        // `create index if not exists` silently did nothing.
        String first = PluginSchemaMigrator.indexName(LONG + "_revisions", "slug", "idx");
        String second = PluginSchemaMigrator.indexName(LONG + "_drafts", "slug", "idx");

        assertThat(first).hasSizeLessThanOrEqualTo(63).endsWith("_slug_idx");
        assertThat(second).hasSizeLessThanOrEqualTo(63).endsWith("_slug_idx");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anOverlongFieldStillYieldsAValidName() {
        String name = PluginSchemaMigrator.indexName("p_wiki_pages", "f".repeat(70), "idx");

        assertThat(name).hasSizeLessThanOrEqualTo(63).endsWith("_idx");
    }
}
