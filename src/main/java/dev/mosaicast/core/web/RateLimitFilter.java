// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Basic rate limiting on auth endpoints and uploads (ARCHITECTURE §13).
 *
 * <p><strong>A filter rather than a check in the controllers</strong>, because half the endpoints that need
 * it are not controllers: {@code /oauth2/authorization/**} and {@code /login/oauth2/code/**} are handled
 * inside Spring Security's own chain, where a thrown {@link TooManyRequestsException} would never reach
 * {@link ApiExceptionHandler}. Running at {@link Ordered#HIGHEST_PRECEDENCE} covers those and the ordinary
 * controller paths with one rule, and refuses a flood before it costs a session lookup or a password hash.
 *
 * <p>The cost of that placement is that this filter writes its own RFC 7807 body — {@code @ControllerAdvice}
 * does not apply out here. It is written by hand rather than through Jackson because the payload is four
 * fixed fields with no caller-supplied text in them, so there is nothing to escape and no reason to reach
 * for a mapper.
 *
 * <p><strong>What this is and is not.</strong> It makes credential stuffing and upload floods expensive, and
 * it stops an accidental client loop from filling a disk. It is not a DoS control: the client key is
 * whatever the deployment resolves the caller to (see {@link #clientKey}), and per §13 the counters live in
 * this instance's memory, so N instances mean N budgets until Redis arrives at v3.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Login and token-minting paths: cheap to request, expensive to serve, and worth guessing at. */
    private static final String[] AUTH_PATHS = {
            "/api/auth/dev-login",
            "/oauth2/authorization/",
            "/login/oauth2/code/",
            "/api/me/tokens",
    };

    /** Upload paths: bounded in size already (§13), bounded in rate here. */
    private static final String[] UPLOAD_PATHS = {
            "/api/admin/branding/",
    };

    private final RateLimitProperties properties;
    private final FixedWindowRateLimiter authLimiter = new FixedWindowRateLimiter();
    private final FixedWindowRateLimiter uploadLimiter = new FixedWindowRateLimiter();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Bucket bucket = bucketFor(request);
        if (bucket == null || !properties.enabledOrDefault()) {
            chain.doFilter(request, response);
            return;
        }

        FixedWindowRateLimiter.Decision decision = bucket.limiter()
                .check(clientKey(request), bucket.limit(), bucket.window(), Instant.now());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // Logged at INFO, not WARN: hitting a limit is the control working, not a fault. The path is
        // included, the client key is not — an operator can see what is being hammered without the log
        // becoming a record of who visited from where.
        log.info("Rate limit reached for {} {}", request.getMethod(), request.getRequestURI());
        refuse(response, decision.retryAfter());
    }

    /** Which budget this request counts against, or null when it counts against none. */
    private Bucket bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();

        // The OAuth2 endpoints are GETs by protocol — a browser redirect out to the provider and back — so
        // they are matched by path regardless of method. Everything else is limited only when it changes
        // state: a GET costs nothing here, and throttling one would punish someone clicking twice.
        boolean stateChanging = HttpMethod.POST.matches(request.getMethod())
                || HttpMethod.PUT.matches(request.getMethod())
                || HttpMethod.DELETE.matches(request.getMethod());

        if (matches(path, AUTH_PATHS) && (stateChanging || isOauthPath(path))) {
            return new Bucket(authLimiter, properties.authLimitOrDefault(), properties.authWindowOrDefault());
        }
        if (matches(path, UPLOAD_PATHS) && stateChanging) {
            return new Bucket(uploadLimiter, properties.uploadLimitOrDefault(),
                    properties.uploadWindowOrDefault());
        }
        return null;
    }

    private static boolean isOauthPath(String path) {
        return path.startsWith("/oauth2/authorization/") || path.startsWith("/login/oauth2/code/");
    }

    private static boolean matches(String path, String[] prefixes) {
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops expired windows so the maps do not grow one entry per client address seen, forever.
     *
     * <p>Hourly against a one-minute window is deliberately lazy: an entry lingering for an extra fifty-nine
     * minutes costs a few dozen bytes, and sweeping on the request path would put work on exactly the
     * requests this class exists to make cheap.
     *
     * <p>Deliberately <strong>not</strong> ShedLock-wrapped, unlike the scheduled work in §5.4: the map it
     * cleans is this instance's own memory, so every instance has to run it. A lock would mean one instance
     * sweeping and the rest growing.
     */
    @Scheduled(fixedDelay = 3_600_000)
    void evictExpiredWindows() {
        Instant now = Instant.now();
        authLimiter.evictExpired(properties.authWindowOrDefault(), now);
        uploadLimiter.evictExpired(properties.uploadWindowOrDefault(), now);
    }

    /**
     * Who to count this request against.
     *
     * <p>{@code getRemoteAddr()} resolves through the deployment's forwarded-header configuration
     * ({@code server.forward-headers-strategy: framework}, application.yml). Behind a proxy that overwrites
     * {@code X-Forwarded-For} that is the real client; on a directly exposed port — which is what the
     * shipped compose file gives you — it is caller-controlled, and rotating the header evades the limit.
     *
     * <p>Counting the socket address instead would trade that for something worse: everyone behind a proxy
     * would share one budget, so a single abusive client would lock out every real user. Between "a
     * determined attacker with header control can evade" and "one attacker can deny service to everybody",
     * this picks the first. It is recorded as a known residual in SECURITY.md rather than papered over.
     */
    private static String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /** The RFC 7807 refusal, matching what {@link ApiExceptionHandler} produces for a 429 elsewhere. */
    private static void refuse(HttpServletResponse response, Duration retryAfter) throws IOException {
        long seconds = Math.max(1, retryAfter.toSeconds());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // JSON is UTF-8. Without this the servlet default (ISO-8859-1) is advertised, which is wrong today
        // for a body that happens to be ASCII and corrupting on the first non-ASCII character anyone adds.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        response.getWriter().write("""
                {"type":"https://mosaicast.dev/problems/too-many-requests",\
                "title":"Too Many Requests","status":429,\
                "detail":"Too many requests. Try again in %d seconds."}""".formatted(seconds));
    }

    /** One budget: which limiter, how many, over how long. */
    private record Bucket(FixedWindowRateLimiter limiter, int limit, Duration window) {
    }
}
