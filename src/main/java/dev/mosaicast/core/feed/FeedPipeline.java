// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates one poll of a feed (ARCHITECTURE §5.2/§5.4): fetch with conditional GET, reconcile any
 * changes, and persist the feed's fresh validators / failure state. A dead feed backs off and its last
 * successful state stays visible — a fetch error never throws out of here.
 */
@Service
public class FeedPipeline {

    private static final Logger log = LoggerFactory.getLogger(FeedPipeline.class);

    private final FeedSourceRegistry registry;
    private final Reconciler reconciler;
    private final FeedRepository feeds;

    public FeedPipeline(FeedSourceRegistry registry, Reconciler reconciler, FeedRepository feeds) {
        this.registry = registry;
        this.reconciler = reconciler;
        this.feeds = feeds;
    }

    /**
     * Polls one feed and reconciles the result.
     *
     * @param feed the feed to poll; a {@code manual} feed (no registered source) is skipped
     * @return the outcome (not-modified, reconciled, skipped, or failed) — never throws for a fetch error
     */
    @Transactional
    public PollOutcome poll(Feed feed) {
        Optional<FeedSource> source = registry.forType(feed.getType());
        if (source.isEmpty() || feed.getUrl() == null) {
            return PollOutcome.skipped();
        }

        SourceConfig cfg = new SourceConfig(feed.getUrl(), feed.getEtag(), feed.getLastModified());
        try {
            FetchResult result = source.get().fetch(cfg);
            if (result.unchanged()) {
                feed.recordSuccess(feed.getEtag(), feed.getLastModified(), "NOT_MODIFIED");
                feeds.save(feed);
                return PollOutcome.notModified();
            }
            ReconcileResult reconciled = reconciler.reconcile(feed.getId(), result.episodes());
            feed.recordSuccess(result.etag(), result.lastModified(), "OK");
            feeds.save(feed);
            log.info("Reconciled feed {} ({}): {}", feed.getId(), feed.getTitle(), reconciled);
            return PollOutcome.reconciled(reconciled);
        } catch (FetchException e) {
            feed.recordFailure(e.getMessage());
            feeds.save(feed);
            log.warn("Feed poll failed for {} ({}): {}", feed.getId(), feed.getTitle(), e.getMessage());
            return PollOutcome.failed(e.getMessage());
        }
    }
}
