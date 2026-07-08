// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Server-side sessions (ARCHITECTURE §8.5): an httpOnly cookie backed by an in-memory store in v1, with
 * <strong>no JWT</strong> so a ban / role change / logout takes effect immediately. The Spring Session
 * abstraction keeps this Redis-swappable from v3 without a rewrite — replace the repository bean, nothing
 * else. The session cookie is {@code SameSite=Lax} and httpOnly; CSRF is handled separately (SecurityConfig).
 */
@Configuration
@EnableSpringHttpSession
public class SessionConfig {

    /** The session cookie name — referenced here and by the logout handler in SecurityConfig. */
    public static final String SESSION_COOKIE_NAME = "MOSAICAST_SESSION";

    @Bean
    MapSessionRepository sessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    /**
     * The session cookie. {@code Secure} defaults to {@code true} (safe for a TLS deployment and for
     * {@code http://localhost}, which browsers treat as a secure context); the {@code dev} profile sets
     * {@code mosaicast.security.secure-cookie=false} for plain-http local runs.
     */
    @Bean
    CookieSerializer cookieSerializer(
            @Value("${mosaicast.security.secure-cookie:true}") boolean secureCookie) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE_NAME);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secureCookie);
        serializer.setCookiePath("/");
        return serializer;
    }
}
