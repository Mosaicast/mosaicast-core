// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.UUID;

/**
 * The public, anonymous detail of one feed for the shell's feed panel (ARCHITECTURE §6.1): the show cover,
 * title, author, description and episode count. Like {@link PublicFeedView} it deliberately omits admin-only
 * state (source URL, poll status, failures).
 *
 * @param id           the internal feed id
 * @param slug         the public identifier the feed is addressed by
 * @param title        the feed's display title
 * @param imageUrl     the show cover ({@code itunes:image}/RSS {@code <image>}), or {@code null}
 * @param author       the show author ({@code itunes:author}), or {@code null}
 * @param description  the channel description, or {@code null}
 * @param episodeCount how many episodes it has
 */
public record FeedDetailView(
        UUID id, String slug, String title, String imageUrl, String author, String description,
        long episodeCount) {

    static FeedDetailView of(Feed feed, long episodeCount) {
        return new FeedDetailView(
                feed.getId(), feed.getSlug(), feed.getTitle(), feed.getImageUrl(), feed.getAuthor(),
                feed.getDescription(), episodeCount);
    }
}
