// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The catalog drafter's rules (ARCHITECTURE §12.7) — the ones that keep a draft reviewable. */
class CatalogDraftRunnerTest {

    @Test
    void pluralKeysAreRecognisedSoTheyAreNeverMachineGenerated() {
        // Which forms exist is a property of the TARGET language — Polish needs three, Arabic six — and no
        // per-string translation can invent them.
        assertThat(CatalogDraftRunner.isPluralKey("feed.episodeCount_one")).isTrue();
        assertThat(CatalogDraftRunner.isPluralKey("feed.episodeCount_other")).isTrue();
        assertThat(CatalogDraftRunner.isPluralKey("admin.languages.missing_few")).isTrue();
        assertThat(CatalogDraftRunner.isPluralKey("app.title")).isFalse();
        assertThat(CatalogDraftRunner.isPluralKey("nav.someoneElse")).isFalse();
    }

    @Test
    void outIsRequiredRatherThanDefaultingToTheLocalesDirectory() {
        // A tool that can overwrite a reviewed catalog with an unreviewed one because somebody forgot a
        // flag should not exist.
        assertThatThrownBy(() -> CatalogDraftArgs.parse(new String[] {"--target=nl"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--out is required");
    }

    @Test
    void refusesAMissingOrSelfReferentialTarget() {
        assertThatThrownBy(() -> CatalogDraftArgs.parse(new String[] {"--out=/tmp/x.json"}))
                .hasMessageContaining("--target is required");
        assertThatThrownBy(() ->
                CatalogDraftArgs.parse(new String[] {"--target=en", "--source=en", "--out=/tmp/x.json"}))
                .hasMessageContaining("same language");
        assertThatThrownBy(() ->
                CatalogDraftArgs.parse(new String[] {"--target=nl", "--out=/tmp/x.json", "--nonsense"}))
                .hasMessageContaining("unknown option");
    }

    @Test
    void parsesTheOrdinaryInvocation() {
        CatalogDraftArgs args = CatalogDraftArgs.parse(
                new String[] {"--target=NL", "--out=./locales/nl.json", "--force"});

        assertThat(args.target()).isEqualTo("nl");
        assertThat(args.source()).as("English is the source language").isEqualTo("en");
        assertThat(args.out()).isEqualTo(Path.of("./locales/nl.json"));
        assertThat(args.force()).isTrue();
    }
}
