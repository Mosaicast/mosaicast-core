// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.plugin.api.EpisodePhase;

/**
 * A write changed an episode's derived phase (core#270): announced, an {@code announceAt} edit either way, a
 * withdrawal, a withdrawn episode returning, or the episode ceasing to exist. Published inside the writing
 * transaction and delivered to plugins only after it commits ({@code PluginContext.onEpisodePhaseChanged},
 * SDK 0.19.0).
 *
 * <p>A <em>release</em> of a planned episode is not published as this event: it is an
 * {@link EpisodeReleasedEvent}, which the dispatcher turns into both hooks, release listeners first. The clock
 * passing {@code announceAt} publishes nothing — there is no write, and becoming visible late is harmless.
 *
 * @param slug  the episode's public slug
 * @param phase the phase after the write, or {@code null} when the episode no longer exists — a cancelled plan,
 *              or a duplicate removed by a match
 */
public record EpisodePhaseChangedEvent(String slug, EpisodePhase phase) {
}
