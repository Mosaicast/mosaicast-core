// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.plugin.api.Role;
import org.springframework.security.core.Authentication;

/**
 * Who may see a quiet planned episode (core#252): the people who plan episodes — podcasters and admins, by
 * session or by access token — and nobody else.
 */
public final class Previews {

    private Previews() {
    }

    /** Whether this caller may see planned episodes that have not been announced yet. */
    public static boolean canSeeQuiet(Authentication authentication) {
        return CurrentUser.role(authentication)
                .map(role -> role == Role.PODCASTER || role == Role.ADMIN)
                .orElse(false);
    }
}
