// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.pat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The personal-access-token chain end to end (ARCHITECTURE §8.5).
 *
 * <p>It had no tests at all — not one of {@code PatAuthenticationFilter},
 * {@code PersonalAccessTokenService} or {@code PatController} was named anywhere under {@code src/test},
 * and nothing called {@code /api/me/tokens} (core#191). That is a second full authentication path, with its
 * own CSRF exemption and a year-long lifetime, carrying no regression protection whatsoever.
 *
 * <p>What is pinned here is the authority a token has, rather than that minting one works: a bearer token
 * is a long-lived, CSRF-exempt, single-factor credential that lives in a CI variable, and an interactive
 * session is none of those things.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PatChainIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void aTokenAuthenticatesItsOwnerWithoutACsrfToken() {
        String secret = mintAs("podcaster");

        ResponseEntity<String> me = rest.exchange("/api/me", HttpMethod.GET, bearer(secret), String.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("PODCASTER");
    }

    @Test
    void aTokenCannotMintItsOwnReplacement() {
        // Bearer auth used to be accepted on the endpoint that *creates* bearer tokens, so revoking a
        // leaked token — the obvious response, and the one the UI invites — left every child it had already
        // created alive, with nothing on screen saying so (core#174).
        String secret = mintAs("podcaster");

        ResponseEntity<String> child = rest.exchange("/api/me/tokens", HttpMethod.POST,
                bearer(secret, "{\"name\":\"child\"}"), String.class);

        assertThat(child.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminsTokenDoesNotReachTheAdminSurface() {
        // §8.5 describes tokens as a podcaster capability for automation. The chain exempts bearer requests
        // from CSRF and keeps them valid for a year, so an admin's token would otherwise be a year-long,
        // header-only key to site config, legal pages, role assignment and erasure — and a token minted
        // while its owner was a podcaster would silently gain all of it the moment they were promoted.
        String secret = mintAs("admin");

        assertThat(rest.exchange("/api/admin/users", HttpMethod.GET, bearer(secret), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The same person, through an interactive session, still administers the site.
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        assertThat(rest.exchange("/api/admin/users", HttpMethod.GET, session(admin), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSessionCookieIsNotExemptedFromCsrfByABearerHeader() {
        // The exemption keyed on the header's presence while CsrfFilter runs before PatAuthenticationFilter,
        // which only fires on an otherwise-anonymous request. So a request with a session cookie *and* any
        // bearer header was authenticated by the cookie and accepted with no CSRF token (core#174).
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, podcaster.session() + "; " + podcaster.xsrf());
        headers.setBearerAuth("mct_not-a-real-token");

        ResponseEntity<String> response = rest.exchange("/api/me/tokens", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"csrf-less\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aRevokedTokenStopsAuthenticating() {
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        String secret = mint(podcaster, "to-revoke");
        String id = idOfNewestToken(podcaster);

        // Through the CSRF-carrying helper: a DELETE from a cookie session is state-changing and the
        // chain requires the header, which is the whole point of the exemption being narrow.
        assertThat(rest.exchange("/api/me/tokens/" + id, HttpMethod.DELETE, write(podcaster, null),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rest.exchange("/api/me", HttpMethod.GET, bearer(secret), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aFanIsNotOfferedTheCapabilityAtAll() {
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");

        assertThat(rest.exchange("/api/me/tokens", HttpMethod.POST,
                write(fan, "{\"name\":\"nope\"}"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String mintAs(String role) {
        return mint(DevLogin.login(rest, role), "automation");
    }

    /** Mints through a real session, the way the account page does. */
    private String mint(DevLogin.Cookies owner, String name) {
        ResponseEntity<String> created = rest.exchange("/api/me/tokens", HttpMethod.POST,
                write(owner, "{\"name\":\"" + name + "\"}"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return between(created.getBody(), "\"secret\":\"", "\"");
    }

    private String idOfNewestToken(DevLogin.Cookies owner) {
        String body = rest.exchange("/api/me/tokens", HttpMethod.GET, session(owner), String.class).getBody();
        return between(body, "\"id\":\"", "\"");
    }

    private static String between(String body, String open, String close) {
        int at = body.indexOf(open) + open.length();
        return body.substring(at, body.indexOf(close, at));
    }

    private HttpEntity<String> bearer(String secret) {
        return bearer(secret, null);
    }

    private HttpEntity<String> bearer(String secret, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(secret);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<String> session(DevLogin.Cookies owner) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, owner.session() + "; " + owner.xsrf());
        return new HttpEntity<>(headers);
    }

    private HttpEntity<String> write(DevLogin.Cookies owner, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, owner.session() + "; " + owner.xsrf());
        headers.add("X-XSRF-TOKEN", owner.xsrf().split("=", 2)[1]);
        return new HttpEntity<>(body, headers);
    }
}
