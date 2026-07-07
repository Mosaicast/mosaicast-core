// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;

/**
 * A non-persisted preview of a feed URL for the add-feed form (ARCHITECTURE §E4 — "validate the URL and
 * show a preview before saving").
 *
 * @param title         the feed's channel title
 * @param episodeCount  number of items in the feed
 * @param sampleTitles  the first few episode titles, for a quick sanity check
 */
public record FeedPreview(String title, int episodeCount, List<String> sampleTitles) {
}
