// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The edge's side of the connection (ARCHITECTURE §18.2).
 *
 * @param token shared with the edge ({@code MOSAICAST_EDGE_TOKEN}); a poll carrying it is recorded as the
 *              edge reporting in. Blank means the admin page shows the edge as unknown.
 */
@ConfigurationProperties(prefix = "mosaicast.edge")
public record EdgeProperties(String token) {

    /** Whether a token is configured at all. */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
