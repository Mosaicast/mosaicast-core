// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The frozen crawler list of ARCHITECTURE §19: a request whose User-Agent contains one of these
 * (lower-cased) substrings, or has none, is not counted. The User-Agent is compared at the moment of
 * counting and never kept.
 */
public final class CrawlerList {

    private static final List<String> SUBSTRINGS = load();

    private CrawlerList() {
    }

    /** Whether a request with this User-Agent is a crawler, link preview or tool. */
    public static boolean isCrawler(@Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        for (String s : SUBSTRINGS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** The substrings, in file order (for the test that pins them against the spec). */
    public static List<String> substrings() {
        return SUBSTRINGS;
    }

    private static List<String> load() {
        try (InputStream in = CrawlerList.class.getResourceAsStream("/metrics/crawlers.txt")) {
            if (in == null) {
                throw new IllegalStateException("metrics/crawlers.txt is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#"))
                    .flatMap(line -> Arrays.stream(line.trim().split("\\s+")))
                    .filter(token -> !token.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("metrics/crawlers.txt is unreadable", e);
        }
    }
}
