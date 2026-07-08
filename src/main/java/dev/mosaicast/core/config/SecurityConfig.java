// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import dev.mosaicast.core.auth.AuthenticatedUserFilter;
import dev.mosaicast.core.auth.DiscordOAuth2UserService;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PatAuthenticationFilter;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
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
 * baseline security headers. The API is deny-by-default — only explicitly public paths and the SPA shell
 * are anonymous; everything else needs the right role.
 */
@Configuration
@EnableMethodSecurity
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
        // Branding + site payload + legal pages are public (needed at boot / in the footer, §12).
        "/branding/**", "/api/site", "/api/legal/**",
    };

    private final DiscordOAuth2UserService discordUserService;
    private final PersonalAccessTokenService tokenService;
    private final UserRepository users;
    private final Environment environment;

    public SecurityConfig(DiscordOAuth2UserService discordUserService,
                          PersonalAccessTokenService tokenService, UserRepository users,
                          Environment environment) {
        this.discordUserService = discordUserService;
        this.tokenService = tokenService;
        this.users = users;
        this.environment = environment;
    }

    /** Shared with the dev-login controller so both persist the SecurityContext the same way. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clientRegistrations)
            throws Exception {

        boolean devProfile = environment.acceptsProfiles(Profiles.of("dev"));

        http
                // SPA CSRF: token in a JS-readable XSRF-TOKEN cookie, echoed back as the X-XSRF-TOKEN
                // header (Spring Security "Integrating with SPAs"). The CsrfCookieFilter forces the token
                // to load per request so the cookie is always set.
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                            // Bearer-token automation carries no cookies, so CSRF does not apply.
                            .ignoringRequestMatchers(PatAuthenticationFilter::hasBearer);
                    // The dev-login bypass is exempt ONLY under the dev profile — where it exists.
                    if (devProfile) {
                        csrf.ignoringRequestMatchers("/api/auth/dev-login");
                    }
                })
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // Bearer auth (sets a bare principal), then the per-request user reload (fills the role and
                // makes role changes / deletion take effect immediately), both before authorization.
                .addFilterBefore(new PatAuthenticationFilter(tokenService), AuthorizationFilter.class)
                .addFilterBefore(new AuthenticatedUserFilter(users), AuthorizationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Public read API (ARCHITECTURE §10 — v1 everything PUBLIC).
                        .requestMatchers(HttpMethod.GET, "/api/feeds/**", "/api/episodes/**").permitAll()
                        // The current user's own account (token creation is further gated by @PreAuthorize).
                        .requestMatchers("/api/me/**").authenticated()
                        // Feeds/planned episodes are a podcaster capability; other admin endpoints are ADMIN.
                        .requestMatchers("/api/admin/feeds/**").hasAnyRole("ADMIN", "PODCASTER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Deny any other API path by default (no accidental fail-open for new endpoints).
                        .requestMatchers("/api/**").denyAll()
                        // The SPA shell and static assets are served openly.
                        .anyRequest().permitAll())
                // Unauthenticated API calls get 401 (not a redirect to a login page).
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, authn) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
                        .deleteCookies(SessionConfig.SESSION_COOKIE_NAME))
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

