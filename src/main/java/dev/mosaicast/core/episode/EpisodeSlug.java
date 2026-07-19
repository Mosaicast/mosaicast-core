// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.text.Normalizer;
import java.util.function.Predicate;

/**
 * Generates a stable, human-readable slug for an episode (ARCHITECTURE §4.1) — the public identifier used in
 * URLs, the episode API and the plugin contract, while the {@link EpisodeRef} UUID stays the internal key.
 *
 * <p>The slug is minted <strong>once at creation and never changes</strong>, so a later feed-title or
 * numbering edit can never re-slug an episode (which would orphan links and plugin data). Shape:
 * {@code {feed}-s{NN}e{NN}} (e.g. {@code the-sample-cast-s01e06}); when the feed declares no season/episode it
 * falls back to the title, then to {@code -ep}; collisions get a numeric suffix ({@code -2}, {@code -3}, …).
 */
public final class EpisodeSlug {

    private static final int FEED_PART_MAX = 40;
    private static final int TITLE_PART_MAX = 40;

    private EpisodeSlug() {
    }

    /**
     * Mints a unique slug.
     *
     * @param feedTitle  the owning feed's title (the readable prefix)
     * @param season     season number, or {@code null}
     * @param episodeNo  episode number, or {@code null}
     * @param title      the episode title (fallback when there is no season/episode)
     * @param exists     tells whether a candidate slug is already taken (DB uniqueness check)
     * @return a slug not currently reported as existing by {@code exists}
     */
    public static String generate(String feedTitle, Integer season, Integer episodeNo, String title,
                                  Predicate<String> exists) {
        String feed = slugify(feedTitle, FEED_PART_MAX);
        if (feed.isEmpty()) {
            feed = "podcast";
        }
        String suffix;
        if (season != null && episodeNo != null) {
            suffix = String.format("s%02de%02d", season, episodeNo);
        } else if (episodeNo != null) {
            suffix = "e" + episodeNo;
        } else {
            String titleSlug = slugify(title, TITLE_PART_MAX);
            suffix = titleSlug.isEmpty() ? "ep" : titleSlug;
        }
        String base = feed + "-" + suffix;

        String candidate = base;
        int n = 2;
        while (exists.test(candidate)) {
            candidate = base + "-" + n;
            n++;
        }
        return candidate;
    }

    /**
     * Lowercases, strips accents, replaces every run of non-alphanumeric characters with a single hyphen, and
     * trims/caps the result. Returns an empty string for {@code null}/blank input.
     */
    public static String slugify(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = noAccents.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > maxLen) {
            slug = slug.substring(0, maxLen).replaceAll("-+$", "");
        }
        return slug;
    }
}
