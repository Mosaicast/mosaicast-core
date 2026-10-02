// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

/**
 * A planned episode has been released: its feed item bound to it (core#252) — automatically by matching
 * numbers, by a confirmed suggestion, or by a manual match. Published inside the binding transaction and
 * delivered to plugins only after it commits ({@code PluginContext.onEpisodeReleased}, SDK 0.18.0).
 *
 * <p>Never for an episode that arrived already released with no plan before it: there was nothing prepared to
 * resolve.
 *
 * @param slug the episode's public slug — the plan's, which the release keeps
 */
public record EpisodeReleasedEvent(String slug) {
}
