// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

/**
 * The open interface for pulling episodes from a source (ARCHITECTURE §5.1) — the extension point that
 * makes other hosts possible (RSS today, Patreon in v2, a native push host later). The platform selects
 * an implementation by {@link #type()} and thereafter reasons only about {@link #capabilities()}.
 */
public interface FeedSource {

    /** The source type this implementation handles, e.g. {@code "rss"}; matched against {@link Feed#getType()}. */
    String type();

    /** What this source can do — the platform queries these, never the type (§5.1). */
    SourceCapabilities capabilities();

    /**
     * Fetches the current items, honoring the conditional-GET validators in {@code cfg} (§5.4).
     *
     * @param cfg the source URL plus last-seen ETag/Last-Modified; never {@code null}
     * @return the fetch outcome — a 304 (nothing changed) or the current items plus fresh validators
     * @throws FetchException if the source cannot be fetched or parsed
     */
    FetchResult fetch(SourceConfig cfg) throws FetchException;
}
