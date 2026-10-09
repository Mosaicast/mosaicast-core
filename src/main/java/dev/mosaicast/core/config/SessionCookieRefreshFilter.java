// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Moves the session cookie's expiry along with the session's.
 *
 * <p>Spring Session writes the cookie only when a session is created, so its {@code Max-Age} counts from the
 * sign-in while the session itself is renewed on every visit. Someone who visits daily would still lose the
 * cookie {@code MOSAICAST_SESSION_TIMEOUT} after signing in. This re-sends it at most once per
 * {@link #INTERVAL}, which is often enough and keeps a {@code Set-Cookie} off almost every response.
 *
 * <p>Only on {@code GET} and {@code HEAD}. A logout or an account deletion expires the cookie further down
 * the chain, and a refresh written first would put two {@code Set-Cookie} headers for one cookie on that
 * response. Browsing is reads, so a daily refresh still happens.
 */
public class SessionCookieRefreshFilter extends OncePerRequestFilter {

    /** How often a live session's cookie is re-sent. */
    static final Duration INTERVAL = Duration.ofDays(1);

    /** When the cookie was last re-sent, in epoch milliseconds, kept on the session itself. */
    static final String REFRESHED_AT = "mc.sessionCookieRefreshedAt";

    private final CookieSerializer cookies;

    public SessionCookieRefreshFilter(CookieSerializer cookies) {
        this.cookies = cookies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        HttpSession session = read ? request.getSession(false) : null;
        if (session != null) {
            long now = System.currentTimeMillis();
            Object last = session.getAttribute(REFRESHED_AT);
            if (!(last instanceof Long at) || now - at >= INTERVAL.toMillis()) {
                cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getId()));
                session.setAttribute(REFRESHED_AT, now);
            }
        }
        chain.doFilter(request, response);
    }
}
