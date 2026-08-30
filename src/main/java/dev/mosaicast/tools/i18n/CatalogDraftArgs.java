// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.tools.i18n;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The catalog drafter's command line.
 *
 * @param target the language to draft
 * @param source the language to translate from
 * @param out    where to write; required, and deliberately so — see {@link CatalogDraftApplication}
 * @param force  overwrite an existing file
 */
public record CatalogDraftArgs(String target, String source, Path out, boolean force) {

    static final String USAGE = """
            usage: draftCatalog --target=<code> --out=<file> [--source=en] [--force]

              --target   the language to draft, e.g. nl
              --out      where to write the draft, e.g. ./locales/nl.draft.json
              --source   the language to translate from (default: en)
              --force    overwrite --out if it already exists

            The result is a DRAFT. Read it before putting it anywhere a visitor will see it: plural forms
            are copied untranslated on purpose, and any string whose {{placeholders}} did not survive the
            round trip is left in the source language and listed at the end.
            """;

    static CatalogDraftArgs parse(String[] args) {
        String target = null;
        String source = "en";
        Path out = null;
        boolean force = false;
        for (String arg : args) {
            if (arg.startsWith("--target=")) {
                target = arg.substring("--target=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--source=")) {
                source = arg.substring("--source=".length()).trim().toLowerCase(Locale.ROOT);
            } else if (arg.startsWith("--out=")) {
                out = Path.of(arg.substring("--out=".length()).trim());
            } else if (arg.equals("--force")) {
                force = true;
            } else {
                throw new IllegalArgumentException("unknown option: " + arg);
            }
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("--target is required");
        }
        if (out == null) {
            // Not defaulted to the locales directory on purpose: a tool that can overwrite a reviewed
            // catalog with an unreviewed one because someone forgot a flag should not exist.
            throw new IllegalArgumentException("--out is required; this writes a draft, not a catalog");
        }
        if (target.equals(source)) {
            throw new IllegalArgumentException("--target and --source are the same language");
        }
        return new CatalogDraftArgs(target, source, out, force);
    }
}
