// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the public {@link EpisodeRef#getSlug() slug} for episodes created before slugs existed
 * (ARCHITECTURE §4.1). Runs once at startup and only touches rows whose slug is null, so it is idempotent.
 * Ordered before the plugin loader ({@code @Order(0)}) so a plugin's {@code register(ctx)} already sees
 * slugs when it resolves episodes in scope.
 */
@Component
@Order(-1)
public class EpisodeSlugBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EpisodeSlugBackfill.class);

    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final FeedRepository feeds;

    public EpisodeSlugBackfill(EpisodeRefRepository refs, EpisodeDisplayRepository displays, FeedRepository feeds) {
        this.refs = refs;
        this.displays = displays;
        this.feeds = feeds;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
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
        log.info("Backfilled slugs for {} episode(s)", missing.size());
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
