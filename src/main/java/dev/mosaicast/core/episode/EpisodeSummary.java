// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * Compact episode view for feed cards and search results (ARCHITECTURE §6.2). Runtime/date come from the
 * feed snapshot (§4.2); the audio URL is intentionally omitted here (detail only). {@code hasAudio}
 * lets the shell show a play affordance without exposing the URL in a list.
 */
public record EpisodeSummary(
        UUID id,
        UUID feedId,
        Integer season,
        Integer episodeNo,
        EpisodeStatus status,
        AccessType access,
        String accessTierRef,
        String title,
        Instant publishedAt,
        Long durationSeconds,
        boolean hasAudio) {

    /** Builds a summary from a ref and its resolved display snapshot. */
    public static EpisodeSummary from(EpisodeRef ref, DisplaySnapshot snapshot) {
        return new EpisodeSummary(
                ref.getId(),
                ref.getFeedId(),
                ref.getSeason(),
                ref.getEpisodeNo(),
                ref.getStatus(),
                ref.getAccessType(),
                ref.getAccessTierRef(),
                snapshot.title(),
                snapshot.publishedAt(),
                snapshot.duration() == null ? null : snapshot.duration().toSeconds(),
                snapshot.audioUrl() != null);
    }
}
