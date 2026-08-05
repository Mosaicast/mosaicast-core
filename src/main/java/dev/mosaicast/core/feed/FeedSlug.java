// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeSlug;
import java.util.function.Predicate;

/**
 * Generates a stable, human-readable slug for a feed — the public identifier used in URLs, the feed API and
 * the plugin contract, while the {@link Feed} UUID stays the internal key. The same arrangement episodes
 * have had since {@code 0.5.2} (ARCHITECTURE §4.1); this closes the gap where an episode lived at
 * {@code /episodes/the-sample-cast-s01e06} but its own show lived at {@code /feeds/7a48fcb5-0fe5-…}.
 *
 * <p>Minted <strong>once at creation and never changed</strong>. A feed's title is fed-derived and can
 * change on any poll, so re-slugging would silently break every link anyone had shared and orphan the
 * plugin data partitioned under the old scope id. An admin who genuinely needs a different URL can delete
 * and re-add the feed, which is the honest version of that operation.
 *
 * <p>Slugification itself is {@link EpisodeSlug#slugify}: one rule for both, so a feed and its episodes
 * cannot disagree about how a title becomes a URL.
 */
public final class FeedSlug {

    private static final int MAX = 60;

    private FeedSlug() {
    }

    /**
     * Mints a unique slug from a feed title.
     *
     * @param title  the feed's display title; blank falls back to {@code podcast}
     * @param exists tells whether a candidate is already taken (DB uniqueness check)
     * @return a slug not currently reported as existing by {@code exists}
     */
    public static String generate(String title, Predicate<String> exists) {
        String base = EpisodeSlug.slugify(title, MAX);
        if (base.isEmpty()) {
            base = "podcast";
        }
        // A slug that is only digits would be ambiguous with nothing today, but a bare number reads as an
        // id rather than a name — prefix it so the URL still looks like a show.
        if (base.matches("\\d+")) {
            base = "podcast-" + base;
        }

        String candidate = base;
        int n = 2;
        while (exists.test(candidate)) {
            candidate = base + "-" + n;
            n++;
        }
        return candidate;
    }
}
