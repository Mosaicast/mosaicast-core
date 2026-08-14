// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code POST /api/auth/dev-login} is CSRF-protected like every other state-changing endpoint (§8.5, §13.5).
 *
 * <p>It used to be exempt under the {@code dev} profile, on the reasoning that the endpoint only exists
 * there. But a dev instance is precisely where a session is worth the most — dev-login mints an ADMIN one on
 * request, with no password to phish — so a cross-site page could put a developer's own browser into a Dev
 * ADMIN session while they were looking at something else. The SPA already sent the header on every unsafe
 * method, so the exemption bought nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class DevLoginCsrfIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void devLoginWithoutACsrfTokenIsRefused() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/auth/dev-login?role=admin", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // No session was minted — the refusal has to happen before the handler, not after it.
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .noneSatisfy(cookie -> assertThat(cookie).startsWith("MOSAICAST_SESSION="));
    }

    @Test
    void devLoginWithAHeaderButNoMatchingCookieIsRefused() {
        // The half a cross-site page can actually manage: it can make the browser send a header, but it
        // cannot read or set the XSRF-TOKEN cookie for this origin, so the two never agree.
        //
        // Its mirror image is deliberately not asserted: a client that sets *both* the cookie and the header
        // to the same arbitrary value is accepted, and that is the double-submit scheme working rather than
        // a gap — the protection is that an attacker cannot write the victim's cookie in the first place.
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-XSRF-TOKEN", "a-token-this-browser-was-never-given");

        assertThat(rest.exchange("/api/auth/dev-login?role=admin", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void devLoginWithACookieButNoHeaderIsRefused() {
        // A plain cross-site form POST: the browser attaches the cookie, nothing supplies the header.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + DevLogin.token(rest));

        assertThat(rest.exchange("/api/auth/dev-login?role=admin", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void devLoginWithAValidTokenStillWorks() {
        DevLogin.Cookies cookies = DevLogin.login(rest, "admin");

        assertThat(cookies.session()).startsWith("MOSAICAST_SESSION=");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.session());
        ResponseEntity<String> me = rest.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("\"role\":\"admin\"");
    }
}
