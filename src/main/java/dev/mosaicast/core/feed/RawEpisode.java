// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.plugin.api.Access;
import java.time.Duration;
import java.time.Instant;

/**
 * One raw item as returned by a {@link FeedSource} before reconciliation (ARCHITECTURE §5.1). The
 * reconciler turns these into {@link dev.mosaicast.core.episode.EpisodeRef}s (identity) plus display
 * snapshots (presentation).
 *
 * @param externalGuid    the source's stable item id; never {@code null}
 * @param title           item title; never {@code null}
 * @param description     item description/show notes; may be empty, never {@code null}
 * @param audioUrl        enclosure audio URL, or {@code null} if the source provides none
 * @param publishedAt     publication timestamp, or {@code null} if absent
 * @param season          {@code itunes:season}, or {@code null}
 * @param episodeNumber   {@code itunes:episode}, or {@code null}
 * @param declaredDuration declared runtime, or {@code null} if the feed declares none
 * @param access          gating requirement; {@link Access#PUBLIC} for RSS in v1
 */
public record RawEpisode(
        String externalGuid,
        String title,
        String description,
        String audioUrl,
        Instant publishedAt,
        Integer season,
        Integer episodeNumber,
        Duration declaredDuration,
        Access access) {
}
