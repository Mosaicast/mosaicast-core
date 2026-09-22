// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import dev.mosaicast.core.auth.pat.PatAuthenticationFilter;
import dev.mosaicast.plugin.api.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-resolves the authenticated user from the database on every request, so role changes and account
 * deletion take effect immediately (ARCHITECTURE §8.5 — the reason for cookie sessions over JWT). Runs
 * after the session context is restored and after the personal-access-token filter, and before
 * authorization.
 *
 * <p>For any authenticated UUID principal (from a cookie session or a bearer token), it reloads the
 * {@link User} and replaces the context authentication with fresh authorities; if the user no longer
 * exists, it clears the authentication so the request is treated as anonymous. This is the single point
 * that turns a stale login into current authority — a demoted podcaster loses admin access on their very
 * next request, and a deleted user is locked out at once.
 *
 * <p>Cost: one lookup per authenticated request; a short-TTL cache is a natural later optimization.
 */
public class AuthenticatedUserFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public AuthenticatedUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication current = context.getAuthentication();
        CurrentUser.id(current).ifPresent(userId ->
                context.setAuthentication(users.findById(userId)
                        .map(user -> CurrentUser.authenticationFor(user, effectiveRole(user, request)))
                        .orElse(null))); // user gone (deleted/banned) → immediately unauthenticated
        chain.doFilter(request, response);
    }

    /**
     * The role this request may act with.
     *
     * <p>It is the user's own role, except that a personal access token never reaches {@code ADMIN}:
     * §8.5 describes tokens as a <em>podcaster</em> capability for automation, and the filter chain
     * exempts bearer requests from CSRF and keeps them valid for a year, so an admin's token would
     * otherwise be a year-long, header-only key to site configuration, legal pages, user roles and
     * erasure. Capping it at {@code PODCASTER} leaves every documented automation (feeds, planned
     * episodes, plugin config) working and takes the admin console out of a CI variable's reach.
     */
    private static Role effectiveRole(User user, HttpServletRequest request) {
        boolean viaToken = Boolean.TRUE.equals(request.getAttribute(PatAuthenticationFilter.PAT_AUTHENTICATED));
        return viaToken && user.getRole() == Role.ADMIN ? Role.PODCASTER : user.getRole();
    }
}
