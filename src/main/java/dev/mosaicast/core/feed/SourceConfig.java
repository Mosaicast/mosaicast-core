// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

/**
 * The input a {@link FeedSource} needs to fetch (ARCHITECTURE §5.1/§5.4). Carries the last-seen
 * conditional-GET validators so a source can send {@code If-None-Match}/{@code If-Modified-Since} and an
 * unchanged feed costs only a 304.
 *
 * @param url          the source URL; never {@code null} for a pollable source
 * @param etag         the last-seen {@code ETag}, or {@code null} on first fetch
 * @param lastModified the last-seen {@code Last-Modified}, or {@code null} on first fetch
 */
public record SourceConfig(String url, String etag, String lastModified) {

    /** A config for a first fetch with no validators. */
    public static SourceConfig initial(String url) {
        return new SourceConfig(url, null, null);
    }
}
