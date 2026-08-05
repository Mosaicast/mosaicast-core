// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing view of a configured feed source, including its last-poll state (ARCHITECTURE §5.4). */
public record FeedView(
        UUID id,
        String slug,
        String type,
        String url,
        String title,
        boolean enabled,
        long pollIntervalSeconds,
        Instant lastFetchedAt,
        String lastFetchStatus,
        String lastError,
        int consecutiveFailures,
        long episodeCount) {

    public static FeedView of(Feed feed, long episodeCount) {
        return new FeedView(
                feed.getId(),
                feed.getSlug(),
                feed.getType(),
                feed.getUrl(),
                feed.getTitle(),
                feed.isEnabled(),
                feed.getPollInterval().toSeconds(),
                feed.getLastFetchedAt(),
                feed.getLastFetchStatus(),
                feed.getLastError(),
                feed.getConsecutiveFailures(),
                episodeCount);
    }
}
