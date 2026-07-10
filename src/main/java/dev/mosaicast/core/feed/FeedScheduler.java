// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically polls due feeds (ARCHITECTURE §5.4). The tick is ShedLock-wrapped so only one instance
 * polls across a load-balanced deployment. Each feed is polled no more often than its own interval, and
 * a failing feed backs off exponentially while its last good state stays visible.
 */
@Component
public class FeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedScheduler.class);

    /** Cap the exponential backoff at 2^5 = 32× the configured interval. */
    private static final int MAX_BACKOFF_SHIFT = 5;

    private final FeedRepository feeds;
    private final FeedPipeline pipeline;

    public FeedScheduler(FeedRepository feeds, FeedPipeline pipeline) {
        this.feeds = feeds;
        this.pipeline = pipeline;
    }

    /**
     * Polls every enabled feed that is due. Runs on a short tick; whether a given feed is actually
     * fetched is gated by {@link #isDue(Feed, Instant)} (its interval + backoff).
     */
    @Scheduled(fixedDelayString = "${mosaicast.feed.poll-tick-ms:60000}")
    @SchedulerLock(name = "feed-poll", lockAtMostFor = "PT9M", lockAtLeastFor = "PT5S")
    public void pollDueFeeds() {
        Instant now = Instant.now();
        for (Feed feed : feeds.findByEnabledTrue()) {
            if (isDue(feed, now)) {
                try {
                    pipeline.poll(feed);
                } catch (RuntimeException e) {
                    // One misbehaving feed must never starve the rest of the tick.
                    log.error("Skipping feed {} ({}) this tick after an error", feed.getId(), feed.getTitle(), e);
                }
            }
        }
    }

    /** A feed is due if it has never been fetched or its (backed-off) interval has elapsed. */
    boolean isDue(Feed feed, Instant now) {
        if (feed.getUrl() == null) {
            return false; // manual feed — nothing to poll
        }
        if (feed.getLastFetchedAt() == null) {
            return true;
        }
        return !feed.getLastFetchedAt().plus(effectiveInterval(feed)).isAfter(now);
    }

    /** The configured interval scaled by an exponential backoff on consecutive failures (§5.4). */
    Duration effectiveInterval(Feed feed) {
        int shift = Math.min(feed.getConsecutiveFailures(), MAX_BACKOFF_SHIFT);
        return feed.getPollInterval().multipliedBy(1L << shift);
    }
}
