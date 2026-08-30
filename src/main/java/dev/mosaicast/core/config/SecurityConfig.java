// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import dev.mosaicast.core.auth.AuthenticatedUserFilter;
import dev.mosaicast.core.auth.DiscordOAuth2UserService;
import dev.mosaicast.core.auth.OAuthLoginFailureHandler;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.auth.pat.PatAuthenticationFilter;
import dev.mosaicast.core.auth.pat.PersonalAccessTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    /**
     * Endpoints the SPA and anonymous visitors may reach without authentication, on any method. The static
     * shell / actuator / branding paths sit outside {@code /api/**} (so an errant non-GET just 405s at the
     * handler), and the auth + OAuth flows legitimately POST — so these stay method-agnostic.
     */
    private static final String[] PUBLIC_PATHS = {
        "/", "/index.html", "/assets/**", "/brand/**", "/favicon.ico",
        "/actuator/health/**", "/actuator/info",
        "/login/**", "/oauth2/**", "/api/auth/**",
        // Branding is public (needed at boot, §12); it lives outside /api so no denyAll fallthrough applies.
        "/branding/**",
        // Plugin frontend bundles are served openly (like branding, outside /api), §7.5.
        "/plugins/**",
    };

    /**
     * Public <em>read</em> API — anonymous but GET-only. These live under {@code /api/**}, so scoping them to
     * GET means a POST/PUT/DELETE falls through to the {@code /api/**} deny-by-default rule (a 401/403) rather
     * than reaching a handler that has no such mapping. The meta payload, the site payload (edited only via
     * {@code /api/admin/site}), and the legal pages (managed via {@code /api/admin/legal}).
     */
    private static final String[] PUBLIC_GET_PATHS = {
        // The consent payload has to be readable before anyone logs in — that is the whole point of a banner.
        "/api/meta", "/api/site", "/api/legal/**", "/api/consent",
        // The language list and the message catalogs: the shell fetches them before anyone logs in, and a
        // plugin reads the list to know which languages it may offer to author in (§12.7).
        "/api/i18n/**",
    };

    private final DiscordOAuth2UserService discordUserService;
    private final PersonalAccessTokenService tokenService;
    private final UserRepository users;
    private final Environment environment;
    private final PluginCspHeaderWriter cspHeaderWriter;

    public SecurityConfig(DiscordOAuth2UserService discordUserService,
                          PersonalAccessTokenService tokenService, UserRepository users,
                          Environment environment, PluginCspHeaderWriter cspHeaderWriter) {
        this.discordUserService = discordUserService;
        this.tokenService = tokenService;
        this.users = users;
        this.environment = environment;
        this.cspHeaderWriter = cspHeaderWriter;
    }

    /**
     * The CSRF token cookie, with the flags {@code withHttpOnlyFalse()} does not set for you.
     *
     * <p>{@code HttpOnly=false} is the design — this is a double-submit token and the SPA has to read it —
     * but Spring's defaults leave {@code SameSite} and {@code Secure} unset, which the audit flagged. Neither
     * is what stops CSRF here (the header echo is), so this is depth rather than the load-bearing control:
     * {@code SameSite=Lax} keeps the token out of cross-site requests in the first place, and {@code Secure}
     * keeps a readable token off a plaintext hop. Both match the session cookie, so the two travel together
     * rather than under different rules.
     *
     * <p>{@code Secure} follows the same {@code mosaicast.security.secure-cookie} switch as the session
     * cookie ({@code SessionConfig}) — on by default, off only for plain-http local runs, where a
     * {@code Secure} cookie would simply never be sent and the SPA could not read its own token.
     */
    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        boolean secure = environment.getProperty("mosaicast.security.secure-cookie", Boolean.class, true);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secure));
        return repository;
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

        http
                // SPA CSRF: token in a JS-readable XSRF-TOKEN cookie, echoed back as the X-XSRF-TOKEN
                // header (Spring Security "Integrating with SPAs"). The CsrfCookieFilter forces the token
                // to load per request so the cookie is always set.
                // dev-login is NOT exempt, though it once was. It is a state-changing POST that mints a
                // session, so without the token a cross-site page could silently drop a developer's browser
                // into a Dev ADMIN session on their own machine — login CSRF, on the one instance where the
                // console is wide open. The SPA sends the header on every unsafe method anyway, so the
                // exemption bought nothing but the hole.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Bearer-token automation carries no cookies, so CSRF does not apply.
                        .ignoringRequestMatchers(PatAuthenticationFilter::hasBearer))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // Bearer auth (sets a bare principal), then the per-request user reload (fills the role and
                // makes role changes / deletion take effect immediately), both before authorization.
                .addFilterBefore(new PatAuthenticationFilter(tokenService), AuthorizationFilter.class)
                .addFilterBefore(new AuthenticatedUserFilter(users), AuthorizationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Public read API (ARCHITECTURE §10 — v1 everything PUBLIC), GET-only so a non-GET
                        // hits the /api/** deny-by-default below instead of a handler-level 405.
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/feeds/**", "/api/episodes/**", "/api/tags",
                                "/api/search").permitAll()
                        // Plugin manifest + doc-store reads are public at the filter (the controller enforces
                        // the plugin's visibleTo read floor); writes require a signed-in user, and the
                        // controller enforces the write-role floor (§7.5/§7.6).
                        .requestMatchers(HttpMethod.GET, "/api/plugins/**").permitAll()
                        // External-service calls are the one plugin write that may legitimately be
                        // anonymous: §16 allows `external.usedBy: "anonymous"`, which is what a site running
                        // a self-hosted LibreTranslate needs for a public translate button. Public at the
                        // filter for that reason and gated in the controller by the declared floor — the same
                        // split the doc-store reads above use. A plugin that declared no `external` block is
                        // a 404 there, so this widens nothing for anyone who did not ask.
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/external/**").permitAll()
                        .requestMatchers("/api/plugins/**").authenticated()
                        // The current user's own account (token creation is further gated by @PreAuthorize).
                        .requestMatchers("/api/me/**").authenticated()
                        // Feeds/planned episodes are a podcaster capability; other admin endpoints are ADMIN.
                        .requestMatchers("/api/admin/feeds/**").hasAnyRole("ADMIN", "PODCASTER")
                        // Pinned related episodes (§6.3) are an editorial decision about episodes, which
                        // §8.5 puts with the podcaster — same reasoning as feeds above.
                        .requestMatchers("/api/admin/episodes/**").hasAnyRole("ADMIN", "PODCASTER")
                        // Plugin config may be delegated to podcasters per field (manifest `editableBy`,
                        // §7.2); the controller enforces which fields this role may actually set. Activation
                        // and purge stay ADMIN via the catch-all below.
                        .requestMatchers("/api/admin/plugins/*/config").hasAnyRole("ADMIN", "PODCASTER")
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
                        // The policy is built per request from the third-party hosts active plugins declared
                        // (§12.5) — the declaration is both the notice and the permission.
                        .addHeaderWriter(cspHeaderWriter)
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

        // Enable Discord login only when a client registration exists (credentials configured).
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(userInfo -> userInfo.userService(discordUserService))
                    .defaultSuccessUrl("/", true)
                    // Preserve the failure reason (account_conflict / link_required) for the shell (§8.3).
                    .failureHandler(new OAuthLoginFailureHandler()));
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

