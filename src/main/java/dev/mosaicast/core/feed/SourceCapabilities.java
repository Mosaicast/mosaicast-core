// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

/**
 * What a {@link FeedSource} can do (ARCHITECTURE §5.1). The rest of the platform queries these
 * capabilities, <strong>never</strong> the source {@code type} — a new host is a new implementation and
 * otherwise zero change.
 *
 * @param providesAudio      the source supplies playable audio (RSS {@code <enclosure>})
 * @param supportsTierGating the source can express tier gating (Patreon in v2; RSS cannot)
 * @param supportsSeasons    the source exposes season numbers ({@code itunes:season})
 * @param pushBased          the source pushes updates and must not be polled (a future native host)
 */
public record SourceCapabilities(
        boolean providesAudio,
        boolean supportsTierGating,
        boolean supportsSeasons,
        boolean pushBased) {
}
