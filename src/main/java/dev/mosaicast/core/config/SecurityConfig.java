// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Baseline HTTP security for the host (ARCHITECTURE §13).
 *
 * <p>M0 scope: apply the baseline security headers (CSP, {@code X-Content-Type-Options},
 * {@code Referrer-Policy}) and leave the API/shell open so the walking skeleton is reachable.
 * The real access rules — {@code oauth2Login}, RBAC, CSRF cookie, the dev-login bypass — replace this
 * permissive chain in M2 (ARCHITECTURE §8). Kept intentionally minimal until then.
 */
@Configuration
public class SecurityConfig {

    /**
     * Content-Security-Policy for the served SPA. The production Vite build emits external JS/CSS
     * (no inline script), so {@code script-src 'self'} is enough; {@code style-src} allows inline for
     * the theme tokens the shell sets on the root element (ARCHITECTURE §12.3).
     */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; "
                    + "img-src 'self' data: https:; "
                    + "media-src 'self' https:; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self'; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Replaced in M2 by the real cookie/CSRF setup (XSRF-TOKEN, SameSite=Lax).
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }
}
