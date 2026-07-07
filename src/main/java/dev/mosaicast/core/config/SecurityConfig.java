// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import dev.mosaicast.core.auth.DiscordOAuth2UserService;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PatAuthenticationFilter;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import java.util.function.Supplier;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP security for the host (ARCHITECTURE §8, §13): Discord {@code oauth2Login} (when configured),
 * server-side cookie sessions (no JWT), CSRF via the {@code XSRF-TOKEN} cookie for the SPA, RBAC, and the
 * baseline security headers. Anonymous read of the public API and the shell stays open; state-changing
 * and admin endpoints require the right role.
 */
@Configuration
public class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; "
                    + "img-src 'self' data: https:; "
                    + "media-src 'self' https:; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self'; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "frame-ancestors 'none'";

    /** Endpoints the SPA and anonymous visitors may reach without authentication. */
    private static final String[] PUBLIC_PATHS = {
        "/", "/index.html", "/assets/**", "/brand/**", "/favicon.ico",
        "/actuator/health/**", "/actuator/info", "/api/meta",
        "/login/**", "/oauth2/**", "/api/auth/**",
    };

    private final DiscordOAuth2UserService discordUserService;
    private final PersonalAccessTokenService tokenService;
    private final UserRepository users;

    public SecurityConfig(DiscordOAuth2UserService discordUserService,
                          PersonalAccessTokenService tokenService, UserRepository users) {
        this.discordUserService = discordUserService;
        this.tokenService = tokenService;
        this.users = users;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clientRegistrations)
            throws Exception {

        http
                // SPA CSRF: token in a JS-readable XSRF-TOKEN cookie, echoed back as the X-XSRF-TOKEN
                // header (Spring Security "Integrating with SPAs"). The CsrfCookieFilter forces the token
                // to load per request so the cookie is always set.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Exempt the dev-login bypass (dev profile only; absent in prod) and bearer-token
                        // automation (no cookies, so CSRF does not apply).
                        .ignoringRequestMatchers("/api/auth/dev-login")
                        .ignoringRequestMatchers(PatAuthenticationFilter::hasBearer))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // Authenticate personal-access-token bearer requests before authorization runs.
                .addFilterBefore(new PatAuthenticationFilter(tokenService, users), AuthorizationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Public read API (ARCHITECTURE §10 — v1 everything PUBLIC).
                        .requestMatchers(HttpMethod.GET, "/api/feeds/**", "/api/episodes/**").permitAll()
                        // Admin/podcaster management.
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "PODCASTER")
                        // The current user's own account.
                        .requestMatchers("/api/me/**").authenticated()
                        // Everything else (SPA routes, static) is served openly.
                        .anyRequest().permitAll())
                // Unauthenticated API calls get 401 (not a redirect to a login page).
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, authn) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
                        .deleteCookies("MOSAICAST_SESSION"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

        // Enable Discord login only when a client registration exists (credentials configured).
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(userInfo -> userInfo.userService(discordUserService))
                    .defaultSuccessUrl("/", true)
                    .failureUrl("/?login_error"));
        }

        return http.build();
    }

    /**
     * The SPA CSRF handler from the Spring Security reference: renders the token XOR-encoded (BREACH
     * mitigation) but resolves the raw token when the client sends it back in the {@code X-XSRF-TOKEN}
     * header (as a JS SPA does after reading the {@code XSRF-TOKEN} cookie).
     */
    static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                Supplier<CsrfToken> csrfToken) {
            // Render (and eagerly load) via the XOR handler so the token attribute/cookie are populated.
            xor.handle(request, response, csrfToken);
        }

        @Override
        public String resolveCsrfTokenValue(
                jakarta.servlet.http.HttpServletRequest request, CsrfToken csrfToken) {
            // Header (SPA) → raw token; body param (form) → XOR-decoded.
            boolean fromHeader = request.getHeader(csrfToken.getHeaderName()) != null;
            return (fromHeader ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
        }
    }

    /**
     * Forces the deferred {@link CsrfToken} to load on every request so the {@code XSRF-TOKEN} cookie is
     * written for the SPA to read (Spring Security "Integrating with SPAs").
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                jakarta.servlet.FilterChain filterChain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
            if (token != null) {
                token.getToken(); // triggers the deferred load → Set-Cookie: XSRF-TOKEN
            }
            filterChain.doFilter(request, response);
        }
    }
}

