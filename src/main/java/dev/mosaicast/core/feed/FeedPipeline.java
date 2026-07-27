// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private final BindingSuggestionRepository suggestions;

    public FeedPipeline(FeedSourceRegistry registry, Reconciler reconciler, FeedRepository feeds,
                        BindingSuggestionRepository suggestions) {
        this.registry = registry;
        this.reconciler = reconciler;
        this.feeds = feeds;
        this.suggestions = suggestions;
    }

    /**
     * Polls one feed and reconciles the result.
     *
     * @param feed the feed to poll; a {@code manual} feed (no registered source) is skipped
     * @return the outcome (not-modified, reconciled, skipped, or failed) — never throws for a fetch error
     */
    @Transactional
    public PollOutcome poll(Feed feed) {
        // Tag everything logged during this poll with the feed it concerns, so the admin log can filter by
        // feed instead of parsing ids out of message text.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("feedId", String.valueOf(feed.getId()))) {
            return pollTagged(feed);
        }
    }

    private PollOutcome pollTagged(Feed feed) {
        // Take a pessimistic lock on the feed row so a scheduler tick and a "refresh now" (or two clicks)
        // can't reconcile the same feed at once and both insert the same GUID (§5.4).
        Feed locked = feeds.lockById(feed.getId())
                .orElseThrow(() -> new IllegalStateException("Feed no longer exists: " + feed.getId()));

        Optional<FeedSource> source = registry.forType(locked.getType());
        if (source.isEmpty() || locked.getUrl() == null) {
            return PollOutcome.skipped();
        }

        SourceConfig cfg = new SourceConfig(locked.getUrl(), locked.getEtag(), locked.getLastModified());
        try {
            FetchResult result = source.get().fetch(cfg);
            if (result.unchanged()) {
                locked.recordSuccess(locked.getEtag(), locked.getLastModified(), "NOT_MODIFIED");
                feeds.save(locked);
                return PollOutcome.notModified();
            }
            ReconcileResult reconciled = reconciler.reconcile(locked.getId(), locked.getTitle(), result.episodes());
            persistSuggestions(locked.getId(), reconciled.suggestions());
            locked.updateChannelMeta(result.feedImageUrl(), result.feedAuthor(), result.feedDescription());
            locked.recordSuccess(result.etag(), result.lastModified(), "OK");
            feeds.save(locked);
            log.info("Reconciled feed {} ({}): {}", locked.getId(), locked.getTitle(), reconciled);
            return PollOutcome.reconciled(reconciled);
        } catch (FetchException e) {
            // A fetch error (thrown before any reconcile write) backs off; the last good state stays visible.
            locked.recordFailure(e.getMessage());
            feeds.save(locked);
            log.warn("Feed poll failed for {} ({}): {}", locked.getId(), locked.getTitle(), e.getMessage());
            return PollOutcome.failed(e.getMessage());
        }
        // Any other RuntimeException (a bug, or a DB error the pessimistic lock did not prevent) propagates;
        // FeedScheduler.pollDueFeeds catches it per-feed so it cannot starve the rest of the tick.
    }

    /**
     * Records the run's fuzzy-title binding proposals for the podcaster to confirm (§5.3) — never applied
     * automatically. Deduped on {@code (feed, planned ref, feed item)} so a suggestion that persists across
     * polls isn't inserted again (and doesn't violate {@code uq_binding_suggestion}).
     */
    private void persistSuggestions(java.util.UUID feedId, java.util.List<ReconcileResult.Suggestion> proposals) {
        for (ReconcileResult.Suggestion proposal : proposals) {
            boolean exists = suggestions
                    .findByFeedIdAndPlannedRefIdAndRawGuid(feedId, proposal.plannedRefId(), proposal.rawGuid())
                    .isPresent();
            if (!exists) {
                suggestions.save(new BindingSuggestion(feedId, proposal));
            }
        }
    }
}
