// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.validation.constraints.NotBlank;

/** Request bodies for feed administration (ARCHITECTURE §5, §4.3). */
public final class FeedRequests {

    private FeedRequests() {
    }

    /** Add an RSS feed. Title is optional — it defaults to the feed's channel title when blank. */
    public record CreateFeed(@NotBlank String url, String title) {
    }

    /** Preview a feed URL without saving it. */
    public record PreviewFeed(@NotBlank String url) {
    }

    /**
     * Create a host-authored planned episode (§4.3): identity now, so bingos can attach before the RSS
     * item exists. Its provisional title/description are authoritative only until the feed item binds.
     */
    public record CreatePlannedEpisode(Integer season, Integer episodeNo, @NotBlank String title, String description) {
    }
}
