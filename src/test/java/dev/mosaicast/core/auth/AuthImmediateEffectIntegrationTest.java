// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.plugin.api.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * End-to-end guards for the review fixes (§13.5), driven through the real filter chain on the {@code dev}
 * profile: a role change takes effect on the very next request (#2, ARCHITECTURE §8.5), and the API is
 * deny-by-default so an unmapped {@code /api} path is refused rather than served (#6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class AuthImmediateEffectIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkedIdentityRepository identities;

    @Test
    void roleChangeTakesEffectOnNextRequest() {
        String session = devLogin("podcaster");

        // As a podcaster, the feed admin endpoint is reachable.
        assertThat(getAdminFeeds(session).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Demote the same user directly, as an admin action would.
        UUID userId = identities.findByProviderAndExternalId("dev", "PODCASTER").orElseThrow().getUserId();
        User user = users.findById(userId).orElseThrow();
        user.changeRole(Role.FAN);
        users.save(user);

        // The existing session immediately loses admin access — no re-login, no waiting for expiry.
        assertThat(getAdminFeeds(session).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unmappedApiPathIsDeniedByDefault() {
        String session = devLogin("fan"); // authenticated, so a denial is 403 (not the anonymous 401)
        ResponseEntity<String> response = rest.exchange(
                "/api/definitely-not-a-real-endpoint", HttpMethod.GET, withSession(session), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void publicReadApiIsGetOnly() {
        // The site payload is anonymously readable...
        assertThat(rest.getForEntity("/api/site", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // ...but an unsafe method is refused, never reaching a handler: CSRF guards it at the front, and the
        // GET-only public matcher leaves it to /api/** deny-by-default behind that (defense in depth).
        ResponseEntity<String> post = rest.postForEntity("/api/site", null, String.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Logs in via the dev bypass and returns the session cookie to reuse. */
    private String devLogin(String role) {
        ResponseEntity<String> response =
                rest.postForEntity("/api/auth/dev-login?role=" + role, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return sessionCookie(response.getHeaders());
    }

    private ResponseEntity<String> getAdminFeeds(String session) {
        return rest.exchange("/api/admin/feeds", HttpMethod.GET, withSession(session), String.class);
    }

    private static HttpEntity<Void> withSession(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return new HttpEntity<>(headers);
    }

    private static String sessionCookie(HttpHeaders headers) {
        return headers.get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("MOSAICAST_SESSION="))
                .map(c -> c.substring(0, c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No session cookie set"));
    }
}
