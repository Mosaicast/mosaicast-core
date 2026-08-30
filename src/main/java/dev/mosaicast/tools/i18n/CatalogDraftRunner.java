// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.tools.i18n;

import dev.mosaicast.core.external.translation.TranslationRequest;
import dev.mosaicast.core.external.translation.TranslationService;
import dev.mosaicast.core.i18n.LocaleCatalog;
import dev.mosaicast.core.i18n.LocaleCatalogSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Does the drafting. See {@link CatalogDraftApplication} for why this is a CLI and not a button. */
@Component
public class CatalogDraftRunner {

    /** i18next interpolation, as the shell's catalogs use it: {@code {{name}}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*[\\w.]+\\s*}}");

    /**
     * i18next plural suffixes.
     *
     * <p>Never machine-generated: which forms exist is a property of the <em>target</em> language — Polish
     * needs three, Arabic six — and no per-string translation can invent them. Copied through and listed
     * so a human writes them.
     */
    private static final List<String> PLURAL_SUFFIXES =
            List.of("_zero", "_one", "_two", "_few", "_many", "_other");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final LocaleCatalogSource catalogs;
    private final TranslationService translation;

    public CatalogDraftRunner(LocaleCatalogSource catalogs, TranslationService translation) {
        this.catalogs = catalogs;
        this.translation = translation;
    }

    /** @return the process exit code */
    public int run(CatalogDraftArgs args) {
        if (!translation.available()) {
            System.err.println("error: no translation provider is configured for this site.");
            System.err.println("Configure one under Admin -> External services first.");
            return 3;
        }
        if (Files.exists(args.out()) && !args.force()) {
            System.err.println("error: " + args.out() + " already exists; pass --force to overwrite.");
            return 4;
        }
        LocaleCatalog source = catalogs.scan().get(args.source());
        if (source == null || source.messages().isEmpty()) {
            System.err.println("error: no catalog for source language '" + args.source() + "'.");
            return 5;
        }

        Map<String, String> drafted = new LinkedHashMap<>();
        List<String> plurals = new ArrayList<>();
        List<String> flagged = new ArrayList<>();
        int translated = 0;

        for (Map.Entry<String, String> entry : source.messages().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (isPluralKey(key)) {
                drafted.put(key, value);
                plurals.add(key);
                continue;
            }
            try {
                String result = translateProtectingPlaceholders(value, args.source(), args.target());
                if (placeholdersOf(result).equals(placeholdersOf(value))) {
                    drafted.put(key, result);
                    translated++;
                } else {
                    // A mangled placeholder means i18next stops substituting and the visitor sees literal
                    // braces. Better to ship the English string and say so.
                    drafted.put(key, value);
                    flagged.add(key);
                }
            } catch (RuntimeException failed) {
                drafted.put(key, value);
                flagged.add(key);
            }
        }

        try {
            write(args, drafted);
        } catch (IOException problem) {
            System.err.println("error: could not write " + args.out() + ": " + problem.getMessage());
            return 6;
        }

        System.out.printf("Drafted %s from %s: %d translated, %d plural key(s) left for a human, "
                + "%d flagged.%n", args.target(), args.source(), translated, plurals.size(), flagged.size());
        report("Plural keys — the target language decides which forms it needs", plurals);
        report("Flagged — left in " + args.source() + ", placeholders did not survive", flagged);
        System.out.println();
        System.out.println("This is a DRAFT. Read it before enabling the language.");
        return 0;
    }

    private static void report(String heading, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(heading + ":");
        keys.forEach(key -> System.out.println("  " + key));
    }

    private void write(CatalogDraftArgs args, Map<String, String> drafted) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        drafted.forEach(root::put);
        if (args.out().getParent() != null) {
            Files.createDirectories(args.out().getParent());
        }
        Files.writeString(args.out(), root.toPrettyString() + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Translates one string with its placeholders hidden from the translator.
     *
     * <p>Each {@code {{name}}} becomes an opaque token that reads as a word, so the engine leaves it alone
     * and — importantly — still produces sensible word order around it. Tokens are restored afterwards.
     */
    private String translateProtectingPlaceholders(String value, String from, String to) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> found = new ArrayList<>();
        StringBuilder masked = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(masked, "MCVAR" + found.size() + "X");
            found.add(matcher.group());
        }
        matcher.appendTail(masked);

        String result = translation.translate(TranslationRequest.of(masked.toString(), from, to)).text();
        for (int i = 0; i < found.size(); i++) {
            // Case-insensitively: some engines title-case a token at the start of a sentence.
            result = result.replaceAll("(?i)MCVAR" + i + "X", Matcher.quoteReplacement(found.get(i)));
        }
        return result;
    }

    private static List<String> placeholdersOf(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group().replaceAll("\\s", ""));
        }
        return found;
    }

    static boolean isPluralKey(String key) {
        return PLURAL_SUFFIXES.stream().anyMatch(key::endsWith);
    }
}
