// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

/**
 * Who sees the site (ARCHITECTURE §18.1). Everything but {@link #OPEN} shows visitors a page from the edge
 * instead of the site and lets staff through.
 */
public enum SiteState {
    /** Everyone sees the site. */
    OPEN,
    /** Closed until {@code opensAt}, open from that instant on. */
    SCHEDULED,
    /** Closed for planned work; {@code endsAt} is shown, never acted on. */
    MAINTENANCE,
    /** Closed with no end in sight. */
    CLOSED
}
