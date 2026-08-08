// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the public {@link EpisodeRef#getSlug() slug} for episodes created before slugs existed
 * (ARCHITECTURE §4.1). Invoked from {@link dev.mosaicast.core.feed.SlugBootstrap} before the HTTP port
 * opens and before the plugin loader, so neither a request nor a plugin's {@code register(ctx)} can observe
 * an episode without a slug. Only rows whose slug is null are touched, so it is idempotent.
 */
@Component
public class EpisodeSlugBackfill {

    private static final Logger log = LoggerFactory.getLogger(EpisodeSlugBackfill.class);

    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final FeedRepository feeds;

    public EpisodeSlugBackfill(EpisodeRefRepository refs, EpisodeDisplayRepository displays, FeedRepository feeds) {
        this.refs = refs;
        this.displays = displays;
        this.feeds = feeds;
    }

    /** Mints slugs for episodes that have none. Idempotent: only rows with a null slug are touched. */
    @Transactional
    public void backfill() {
        List<EpisodeRef> missing = refs.findBySlugIsNull();
        if (missing.isEmpty()) {
            return;
        }
        for (EpisodeRef ref : missing) {
            String feedTitle = feeds.findById(ref.getFeedId()).map(f -> f.getTitle()).orElse("");
            String slug = EpisodeSlug.generate(
                    feedTitle, ref.getSeason(), ref.getEpisodeNo(), titleOf(ref), refs::existsBySlug);
            ref.assignSlugIfAbsent(slug);
            refs.save(ref);
        }
        // Report what landed, not what was attempted.
        //
        // This line used to count the rows it had *selected*, which is how the mapping bug below it went unseen
        // for a whole release: `slug` was mapped `updatable = false`, Hibernate dropped the column from every
        // UPDATE, and the log cheerfully announced a backfill that had written nothing on every single boot.
        // A startup task that can only report success is a task nobody can tell is broken.
        refs.flush();
        long remaining = refs.countBySlugIsNull();
        if (remaining > 0) {
            log.error(
                    "Slug backfill incomplete: {} of {} episode(s) still have no slug. Those episodes have no "
                            + "public URL, are absent from the sitemap and are invisible to plugins.",
                    remaining,
                    missing.size());
        } else {
            log.info("Backfilled slugs for {} episode(s)", missing.size());
        }
    }

    /** The best available title for a legacy ref: its provisional display, else the feed snapshot, else blank. */
    private String titleOf(EpisodeRef ref) {
        if (ref.getProvisionalDisplay() != null) {
            return ref.getProvisionalDisplay().title();
        }
        return displays.findById(ref.getId())
                .map(EpisodeDisplay::getSnapshot)
                .map(DisplaySnapshot::title)
                .orElse("");
    }
}
