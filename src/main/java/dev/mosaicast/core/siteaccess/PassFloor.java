// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import dev.mosaicast.plugin.api.Role;

/**
 * The lowest role the edge lets through while the site is not open (ARCHITECTURE §18.1). ADMIN always
 * passes, so there is no floor above it and no way to lock every admin out of their own site.
 */
public enum PassFloor {
    PODCASTER,
    ADMIN;

    /** Whether someone with {@code role} gets through. */
    public boolean admits(Role role) {
        return role == Role.ADMIN || (this == PODCASTER && role == Role.PODCASTER);
    }
}
