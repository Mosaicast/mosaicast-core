// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import dev.mosaicast.core.auth.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates automation requests carrying a personal access token as {@code Authorization: Bearer …}
 * (ARCHITECTURE §8.5). It resolves the token to its owner's id and sets a bare principal; the downstream
 * {@link dev.mosaicast.core.auth.AuthenticatedUserFilter} then loads the user and fills in the role (so the
 * user is looked up once, and revocation is honored). Nothing is written to the session. A cookie session,
 * if already present, wins — the bearer path only runs when the request is otherwise anonymous.
 */
public class PatAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PersonalAccessTokenService tokens;

    public PatAuthenticationFilter(PersonalAccessTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!CurrentUser.isAuthenticated(SecurityContextHolder.getContext().getAuthentication())
                && hasBearer(request)) {
            String secret = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length());
            tokens.authenticate(secret).ifPresent(token -> {
                // Bare principal (id only, no authorities) — AuthenticatedUserFilter loads the user and role.
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        token.getUserId().toString(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }

    /** True when the request presents a bearer token — also used to exempt these requests from CSRF. */
    public static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER);
    }
}
