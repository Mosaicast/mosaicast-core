// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.text.Normalizer;
import java.util.Locale;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/**
 * Fuzzy title matching for the reconciler's PLANNED binding (ARCHITECTURE §5.3). When a planned episode
 * has no declared season/episode number to match on, a fuzzy title comparison proposes a binding
 * <em>suggestion</em> for the podcaster to confirm — never an automatic merge.
 *
 * <p>Titles are normalized (lowercased, accent- and punctuation-stripped, whitespace-collapsed) before a
 * Jaro-Winkler comparison, so cosmetic differences ("Ep. 12: Pigeons!" vs "Pigeons") do not sink an
 * otherwise-strong match. A separate class so the matching is unit-tested in isolation (a §13.5 risk point).
 */
public final class TitleSimilarity {

    /** Default similarity threshold above which a fuzzy title pair is a binding candidate. */
    public static final double DEFAULT_THRESHOLD = 0.85;

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    private TitleSimilarity() {
    }

    /**
     * Similarity of two titles in {@code [0.0, 1.0]} after normalization. Two blank titles score 0
     * (nothing to match on), never a false 1.0.
     */
    public static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        return JARO_WINKLER.apply(na, nb);
    }

    /** True when {@link #similarity(String, String)} meets or exceeds {@link #DEFAULT_THRESHOLD}. */
    public static boolean matches(String a, String b) {
        return similarity(a, b) >= DEFAULT_THRESHOLD;
    }

    /**
     * Normalizes a title for comparison: strips accents, lowercases, replaces every run of non-alphanumeric
     * characters with a single space, and trims. Returns an empty string for {@code null}.
     */
    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
