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
        String slug,
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
        String excerpt,
        Instant publishedAt,
        Long durationSeconds,
        boolean hasAudio) {

    /** Max characters of the description surfaced as a card excerpt (the shell clamps visually too). */
    private static final int EXCERPT_LIMIT = 300;

    /** Builds a summary from a ref and its resolved display snapshot. */
    public static EpisodeSummary from(EpisodeRef ref, DisplaySnapshot snapshot) {
        return new EpisodeSummary(
                ref.getId(),
                ref.getSlug(),
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
                excerpt(snapshot.description()),
                snapshot.publishedAt(),
                snapshot.duration() == null ? null : snapshot.duration().toSeconds(),
                snapshot.audioUrl() != null);
    }

    /** A short plain-text lead-in for the card: HTML stripped, whitespace collapsed, length-capped. */
    private static String excerpt(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String text = description.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT).trim() + "…";
    }
}
