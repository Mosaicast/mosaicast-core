// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.log.LogSafe;
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
    private final FeedPollStore store;

    public FeedPipeline(FeedSourceRegistry registry, FeedPollStore store) {
        this.registry = registry;
        this.store = store;
    }

    /** Elapsed milliseconds since a {@code System.nanoTime()} mark — poll timings are worth having. */
    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * Polls one feed and reconciles the result.
     *
     * @param feed the feed to poll; one whose type has no registered source is skipped
     * @return the outcome (not-modified, reconciled, skipped, or failed) — never throws for a fetch error
     */
    /**
     * Deliberately <strong>not</strong> {@code @Transactional}.
     *
     * <p>The fetch happens between two short transactions rather than inside one — see {@link FeedPollStore}
     * for why, and for what that trades away. Anything that calls this must not wrap it in a transaction of
     * its own either, or the store's transactions join that one and the network call is back inside the lock.
     */
    public PollOutcome poll(Feed feed) {
        // Tag everything logged during this poll with the feed it concerns, so the admin log can filter by
        // feed instead of parsing ids out of message text.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("feedId", String.valueOf(feed.getId()))) {
            return pollTagged(feed.getId());
        }
    }

    private PollOutcome pollTagged(java.util.UUID feedId) {
        long startedAt = System.nanoTime();
        Optional<FeedPollStore.PollTarget> target = store.target(feedId);
        if (target.isEmpty()) {
            return PollOutcome.skipped();
        }
        FeedPollStore.PollTarget poll = target.get();
        Optional<FeedSource> source = registry.forType(poll.type());
        if (source.isEmpty() || poll.config().url() == null) {
            return PollOutcome.skipped();
        }

        try {
            // No transaction, no connection, no row lock is held across this.
            FetchResult result = source.get().fetch(poll.config());
            if (result.unchanged()) {
                store.applyNotModified(feedId);
                log.info("Polled feed '{}': unchanged (304) in {} ms", LogSafe.of(poll.title()), millisSince(startedAt));
                return PollOutcome.notModified();
            }
            ReconcileResult reconciled = store.applyChanged(feedId, result);
            log.info("Polled feed '{}': {} item(s) fetched in {} ms — {} new, {} updated, {} withdrawn, "
                            + "{} bound to planned, {} suggestion(s)",
                    LogSafe.of(poll.title()), result.episodes().size(), millisSince(startedAt),
                    reconciled.created(), reconciled.updated(), reconciled.withdrawn(), reconciled.bound(),
                    reconciled.suggestions().size());
            return PollOutcome.reconciled(reconciled);
        } catch (FetchException e) {
            // A fetch error (thrown before any reconcile write) backs off; the last good state stays visible.
            int failures = store.applyFailure(feedId, e.getMessage());
            log.warn("Feed poll failed for '{}' after {} ms ({} consecutive failure(s), next attempt backs "
                            + "off): {}",
                    LogSafe.of(poll.title()), millisSince(startedAt), failures, e.getMessage());
            return PollOutcome.failed(e.getMessage());
        }
        // Any other RuntimeException (a bug, or a DB error the pessimistic lock did not prevent) propagates;
        // FeedScheduler.pollDueFeeds catches it per-feed so it cannot starve the rest of the tick.
    }
}
