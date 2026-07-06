// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

/**
 * The gating kind of an {@link EpisodeRef} (ARCHITECTURE §4.1/§10). In v1 (RSS only) everything is
 * {@link #PUBLIC}; {@link #TIER} (with a tier reference) arrives with Patreon in v2. The host, never a
 * plugin, decides whether a user satisfies the requirement.
 */
public enum AccessType {
    PUBLIC,
    TIER
}
