// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Logs a test in through the dev bypass, carrying a CSRF token — because {@code /api/auth/dev-login} is a
 * state-changing POST like any other and is no longer exempt.
 *
 * <p>Shared rather than copied into each integration test because the sequence is easy to get subtly wrong,
 * and getting it wrong is expensive in a way that is hard to see: a token taken from the wrong point in the
 * flow yields a **403 that looks exactly like an authorization failure**. A security audit of this app read
 * two such 403s as "podcaster may not write feeds" and reported a role gap that does not exist.
 *
 * <p>The sequence: one public GET to make the chain mint a token and set the {@code XSRF-TOKEN} cookie, then
 * the POST echoing it back in the {@code X-XSRF-TOKEN} header — which is exactly what the SPA does.
 */
public final class DevLogin {

    /** The path used to obtain a token: public, GET, and always present. */
    private static final String TOKEN_SOURCE = "/api/meta";

    private DevLogin() {
    }

    /**
     * Logs in as {@code role} and returns the session and CSRF cookies to reuse.
     *
     * @param rest the test client
     * @param role {@code admin}, {@code podcaster} or {@code fan}
     * @return the cookies, ready to attach to a subsequent request
     */
    public static Cookies login(TestRestTemplate rest, String role) {
        String token = token(rest);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        ResponseEntity<String> response = rest.exchange(
                "/api/auth/dev-login?role=" + role, HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        // The token cookie survives the login (it lives in a cookie, not the session), so fall back to the
        // one we already hold if the response did not re-set it.
        String xsrf = cookie(setCookies, "XSRF-TOKEN").orElse("XSRF-TOKEN=" + token);
        return new Cookies(cookie(setCookies, "MOSAICAST_SESSION")
                .orElseThrow(() -> new IllegalStateException("dev-login set no session cookie")), xsrf);
    }

    /** A fresh CSRF token from the chain, for a request that needs one without a session. */
    public static String token(TestRestTemplate rest) {
        ResponseEntity<String> primer = rest.getForEntity(TOKEN_SOURCE, String.class);
        List<String> cookies = primer.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("GET %s must set the XSRF-TOKEN cookie", TOKEN_SOURCE).isNotNull();
        String pair = cookie(cookies, "XSRF-TOKEN")
                .orElseThrow(() -> new IllegalStateException("No XSRF-TOKEN cookie set by " + TOKEN_SOURCE));
        return pair.substring(pair.indexOf('=') + 1);
    }

    private static java.util.Optional<String> cookie(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.contains(";") ? c.substring(0, c.indexOf(';')) : c)
                .findFirst();
    }

    /**
     * A logged-in session's two cookies, as {@code name=value} pairs.
     *
     * @param session the {@code MOSAICAST_SESSION} cookie pair
     * @param xsrf    the {@code XSRF-TOKEN} cookie pair
     */
    public record Cookies(String session, String xsrf) {

        /** The bare CSRF token, for the {@code X-XSRF-TOKEN} header. */
        public String token() {
            return xsrf.substring(xsrf.indexOf('=') + 1);
        }
    }
}
