// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.plugin.api.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Reads the current user's id and role from the {@link Authentication}, uniformly across the two ways a
 * session is established: Discord {@code oauth2Login} and the {@code dev}-profile login bypass. Both set
 * the principal name to the user's UUID and carry a single {@code ROLE_*} authority.
 */
public final class CurrentUser {

    private static final String ROLE_PREFIX = "ROLE_";

    private CurrentUser() {
    }

    /** The authenticated user's id, or empty for an anonymous request. */
    public static Optional<UUID> id(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The authenticated user's role, or empty for an anonymous request. */
    public static Optional<Role> role(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            return Optional.empty();
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(ROLE_PREFIX)) {
                try {
                    return Optional.of(Role.valueOf(value.substring(ROLE_PREFIX.length())));
                } catch (IllegalArgumentException ignored) {
                    // not one of our roles
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
