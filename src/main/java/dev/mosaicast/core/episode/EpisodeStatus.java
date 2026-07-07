// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

/**
 * Lifecycle of an {@link EpisodeRef} (ARCHITECTURE §4.1). {@code PLANNED} is the bingo prediction phase
 * (host-created before the feed), {@code PUBLISHED} the resolution (bound to a real feed item), and
 * {@code WITHDRAWN} an item that disappeared from the feed — never hard-deleted so plugin data and
 * feeds do not orphan (§5.2).
 */
public enum EpisodeStatus {
    PLANNED,
    PUBLISHED,
    WITHDRAWN
}
