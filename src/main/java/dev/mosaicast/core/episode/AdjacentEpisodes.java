// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

/**
 * The previous/next episode in a feed's canonical sequence (ARCHITECTURE §6.2) — fixed core navigation,
 * shown on the detail page and used by the player's auto-advance. Either side is {@code null} at the ends
 * of the sequence.
 *
 * @param prev the preceding episode, or {@code null} if this is the first
 * @param next the following episode, or {@code null} if this is the last
 */
public record AdjacentEpisodes(EpisodeSummary prev, EpisodeSummary next) {
}
