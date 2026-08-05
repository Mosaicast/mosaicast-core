// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.UUID;

/**
 * The public, anonymous view of a feed for the shell's catalog (ARCHITECTURE §6.1). Deliberately slim:
 * only what a visitor may see — id, title, and episode count. The admin-facing {@link FeedView} (source
 * URL, last-poll state, error/failure counters) is <strong>not</strong> exposed here and must not leak to
 * unauthenticated callers. The shell derives a cover client-side from the id (no cover field needed).
 *
 * @param id           the internal feed id (still used to filter episode queries and derive a cover)
 * @param slug         the public identifier the feed is addressed by in URLs and the plugin contract
 * @param title        the feed's display title
 * @param episodeCount how many episodes it has (includes non-published; a rough size hint)
 */
public record PublicFeedView(UUID id, String slug, String title, long episodeCount) {

    static PublicFeedView of(Feed feed, long episodeCount) {
        return new PublicFeedView(feed.getId(), feed.getSlug(), feed.getTitle(), episodeCount);
    }
}
