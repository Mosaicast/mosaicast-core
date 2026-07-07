// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import dev.mosaicast.core.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates automation requests carrying a personal access token as {@code Authorization: Bearer …}
 * (ARCHITECTURE §8.5). The request is authenticated as the token's owner with their role; nothing is
 * written to the session (stateless per request). A cookie session, if already present, wins — the bearer
 * path only runs when the request is otherwise anonymous.
 */
public class PatAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final PersonalAccessTokenService tokens;
    private final UserRepository users;

    public PatAuthenticationFilter(PersonalAccessTokenService tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!alreadyAuthenticated() && hasBearer(request)) {
            String secret = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER.length());
            tokens.authenticate(secret).ifPresent(token -> users.findById(token.getUserId())
                    .ifPresent(user -> {
                        var authorities = Set.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                                user.getId().toString(), null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }));
        }
        chain.doFilter(request, response);
    }

    /** True when the request presents a bearer token — also used to exempt these requests from CSRF. */
    public static boolean hasBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith(BEARER);
    }

    private static boolean alreadyAuthenticated() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current != null && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken);
    }
}
