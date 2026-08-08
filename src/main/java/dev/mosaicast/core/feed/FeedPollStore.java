// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of a feed poll, kept in its own bean so the network half can happen <em>between</em> its
 * transactions rather than inside one.
 *
 * <p>A poll used to run as a single transaction: take a pessimistic lock on the {@code feed} row, then perform
 * the outbound HTTP fetch, then reconcile. That pinned a JDBC connection and a row lock for the entire remote
 * round-trip — up to the full fetch budget, and longer against a host that trickles. With HikariCP's default
 * pool of ten, a podcaster clicking "Refresh now" on eight slow feeds took the pool down and the next public
 * page load returned a 500. The scheduler tick did the same thing serially.
 *
 * <p>Splitting it costs one thing worth stating: two concurrent polls of the same feed can now both fetch,
 * because neither holds the lock while doing so. They still serialise on {@link #applyChanged}, and the
 * reconciler keys on the item GUID, so the second one updates what the first inserted rather than duplicating
 * it. A redundant fetch is cheap; a starved connection pool is not.
 *
 * <p>Separate bean rather than separate methods because Spring's {@code @Transactional} is proxy-based — a
 * self-call would silently keep everything in one transaction and quietly undo the fix.
 */
@Component
class FeedPollStore {

    private final FeedRepository feeds;
    private final Reconciler reconciler;
    private final BindingSuggestionRepository suggestions;

    FeedPollStore(FeedRepository feeds, Reconciler reconciler, BindingSuggestionRepository suggestions) {
        this.feeds = feeds;
        this.reconciler = reconciler;
        this.suggestions = suggestions;
    }

    /** What the fetch needs, read without a lock: nothing is being written yet. */
    @Transactional(readOnly = true)
    Optional<PollTarget> target(UUID feedId) {
        return feeds.findById(feedId)
                .map(feed -> new PollTarget(feed.getId(), feed.getType(), feed.getTitle(),
                        new SourceConfig(feed.getUrl(), feed.getEtag(), feed.getLastModified())));
    }

    /** Records a 304: validators stay as they were, the failure counter resets. */
    @Transactional
    void applyNotModified(UUID feedId) {
        Feed locked = lock(feedId);
        locked.recordSuccess(locked.getEtag(), locked.getLastModified(), "NOT_MODIFIED");
        feeds.save(locked);
    }

    /** Reconciles a changed feed and stores its fresh validators. Holds the lock only for this. */
    @Transactional
    ReconcileResult applyChanged(UUID feedId, FetchResult result) {
        Feed locked = lock(feedId);
        ReconcileResult reconciled = reconciler.reconcile(locked.getId(), locked.getTitle(), result.episodes());
        persistSuggestions(locked.getId(), reconciled.suggestions());
        locked.updateChannelMeta(result.feedImageUrl(), result.feedAuthor(), result.feedDescription());
        locked.recordSuccess(result.etag(), result.lastModified(), "OK");
        feeds.save(locked);
        return reconciled;
    }

    /** Records a failed fetch so the next attempt backs off. The last good state stays visible. */
    @Transactional
    int applyFailure(UUID feedId, String message) {
        Feed locked = lock(feedId);
        locked.recordFailure(message);
        feeds.save(locked);
        return locked.getConsecutiveFailures();
    }

    /**
     * Pessimistic lock on the feed row, so a scheduler tick and a "refresh now" (or two clicks) cannot
     * reconcile the same feed at once and both insert the same GUID (§5.4).
     */
    private Feed lock(UUID feedId) {
        return feeds.lockById(feedId)
                .orElseThrow(() -> new IllegalStateException("Feed no longer exists: " + feedId));
    }

    /**
     * Records the run's fuzzy-title binding proposals for the podcaster to confirm (§5.3) — never applied
     * automatically. Deduped on {@code (feed, planned ref, feed item)} so a suggestion that persists across
     * polls isn't inserted again (and doesn't violate {@code uq_binding_suggestion}).
     */
    private void persistSuggestions(UUID feedId, List<ReconcileResult.Suggestion> proposals) {
        for (ReconcileResult.Suggestion proposal : proposals) {
            boolean exists = suggestions
                    .findByFeedIdAndPlannedRefIdAndRawGuid(feedId, proposal.plannedRefId(), proposal.rawGuid())
                    .isPresent();
            if (!exists) {
                suggestions.save(new BindingSuggestion(feedId, proposal));
            }
        }
    }

    /** What a poll needs in hand before it goes near the network. */
    record PollTarget(UUID feedId, String type, String title, SourceConfig config) {
    }
}
