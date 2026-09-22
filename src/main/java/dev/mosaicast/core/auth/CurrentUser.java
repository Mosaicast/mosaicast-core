// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.plugin.api.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Reads and builds the current user's {@link Authentication}, uniformly across the ways a session is
 * established: Discord {@code oauth2Login}, the {@code dev}-profile login bypass, and personal-access-token
 * bearer auth. All of them set the principal name to the user's UUID and carry a single {@code ROLE_*}
 * authority — this class is the one place that shape is produced ({@link #authenticationFor}) and read
 * ({@link #id}/{@link #role}), so the two never drift.
 */
public final class CurrentUser {

    private static final String ROLE_PREFIX = "ROLE_";

    private CurrentUser() {
    }

    /** The single {@code ROLE_*} authority for a role. */
    public static Collection<GrantedAuthority> authoritiesFor(Role role) {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + role.name()));
    }

    /** A uniform authentication for a user: principal name = user id, one {@code ROLE_*} authority. */
    public static Authentication authenticationFor(User user) {
        return authenticationFor(user, user.getRole());
    }

    /**
     * The same shape, but carrying an explicitly chosen role rather than the user's own.
     *
     * <p>Used where the credential is weaker than the account: a personal access token authenticates the
     * right person and still must not act as one (see {@link AuthenticatedUserFilter}).
     */
    public static Authentication authenticationFor(User user, Role role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                user.getId().toString(), null, authoritiesFor(role));
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

    /** True when the request carries a real (non-anonymous) authentication. */
    public static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
