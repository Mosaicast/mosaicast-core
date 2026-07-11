// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * Full episode view for the detail page (ARCHITECTURE §6.2). Adds description and the audio URL to the
 * {@link EpisodeSummary} fields. All presentation data comes from the feed snapshot (or the provisional
 * display while PLANNED, §4.3) — never from plugin metrics.
 */
public record EpisodeDetail(
        UUID id,
        UUID feedId,
        Integer season,
        Integer episodeNo,
        EpisodeStatus status,
        AccessType access,
        String accessTierRef,
        String title,
        String subtitle,
        String author,
        String imageUrl,
        String description,
        Instant publishedAt,
        Long durationSeconds,
        String audioUrl) {

    /** Builds a detail view from a ref and its resolved display snapshot. */
    public static EpisodeDetail from(EpisodeRef ref, DisplaySnapshot snapshot) {
        return new EpisodeDetail(
                ref.getId(),
                ref.getFeedId(),
                ref.getSeason(),
                ref.getEpisodeNo(),
                ref.getStatus(),
                ref.getAccessType(),
                ref.getAccessTierRef(),
                snapshot.title(),
                snapshot.subtitle(),
                snapshot.author(),
                snapshot.artwork(), // episode image, falling back to the feed cover (§4.2)
                snapshot.description(),
                snapshot.publishedAt(),
                snapshot.duration() == null ? null : snapshot.duration().toSeconds(),
                snapshot.audioUrl());
    }
}
