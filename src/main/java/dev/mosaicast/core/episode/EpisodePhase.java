// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

/**
 * Where an episode stands in its release cycle (core#252) — derived, never stored.
 *
 * <p>{@link EpisodeStatus} is what the identity layer records; the phase adds the one thing status cannot
 * say on its own, whether a planned episode has been announced yet. It is computed against the current time,
 * so a scheduled announcement needs no job to flip it.
 */
public enum EpisodePhase {
    /** Planned and not yet announced: visible to podcasters and admins only. */
    PLANNED,
    /** Planned and announced: public, shown as upcoming, no audio yet. */
    UPCOMING,
    /** The feed item has arrived and bound. */
    RELEASED,
    /** Gone from the feed; kept so plugin data does not orphan. */
    WITHDRAWN
}
