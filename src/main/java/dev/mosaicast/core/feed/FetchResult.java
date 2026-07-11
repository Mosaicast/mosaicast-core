// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;

/**
 * The outcome of a {@link FeedSource#fetch(SourceConfig)} (ARCHITECTURE §5.4). Either the feed was
 * unchanged ({@link #notModified()} — a 304, nothing to reconcile) or it carries the current items plus
 * the fresh conditional-GET validators to store for next time.
 *
 * <p>This extends the ARCHITECTURE §5.1 sketch ({@code List<RawEpisode> fetch(...)}) so conditional GET
 * (§5.4) is first-class rather than bolted on.
 *
 * @param unchanged    true when the source reported no change (304); {@link #episodes} is then empty
 * @param episodes     the current items; never {@code null}, empty when {@code unchanged}
 * @param etag         the fresh {@code ETag} to persist, or {@code null}
 * @param lastModified the fresh {@code Last-Modified} to persist, or {@code null}
 * @param feedTitle       the source's channel/feed title, or {@code null} (used to prefill on add, §E4)
 * @param feedImageUrl    the channel cover ({@code itunes:image}), or {@code null}
 * @param feedAuthor      the channel author ({@code itunes:author}), or {@code null}
 * @param feedDescription the channel description, or {@code null}
 */
public record FetchResult(
        boolean unchanged, List<RawEpisode> episodes, String etag, String lastModified, String feedTitle,
        String feedImageUrl, String feedAuthor, String feedDescription) {

    public FetchResult {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }

    /** The feed was unchanged since the last fetch (HTTP 304). */
    public static FetchResult notModified() {
        return new FetchResult(true, List.of(), null, null, null, null, null, null);
    }

    /** The feed changed: carry its items, channel metadata, and the fresh validators. */
    public static FetchResult changed(
            List<RawEpisode> episodes, String etag, String lastModified, String feedTitle,
            String feedImageUrl, String feedAuthor, String feedDescription) {
        return new FetchResult(false, episodes, etag, lastModified, feedTitle,
                feedImageUrl, feedAuthor, feedDescription);
    }
}
