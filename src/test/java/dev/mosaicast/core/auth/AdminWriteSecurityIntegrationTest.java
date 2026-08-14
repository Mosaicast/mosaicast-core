// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives a mutating admin endpoint ({@code PUT /api/admin/site}) through the real security + CSRF chain on
 * the {@code dev} profile (ARCHITECTURE §8, §13.5), proving the wiring on writes: an ADMIN with a valid CSRF
 * token succeeds; the same write without the token is refused (CSRF); and a PODCASTER is refused (RBAC —
 * the site editor is ADMIN-only). Complements {@link AuthImmediateEffectIntegrationTest}, which covers reads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class AdminWriteSecurityIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SITE_PAYLOAD =
            "{\"siteName\":\"Renamed Site\",\"accentSeed\":\"#3366cc\",\"modePolicy\":\"dark\"}";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void adminWithCsrfTokenCanWrite() {
        Session session = devLogin("admin");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/site", HttpMethod.PUT, session.write(SITE_PAYLOAD, true), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminWithoutCsrfTokenIsForbidden() {
        Session session = devLogin("admin");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/site", HttpMethod.PUT, session.write(SITE_PAYLOAD, false), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void podcasterCannotWriteAdminOnlyEndpoint() {
        Session session = devLogin("podcaster");
        // Podcaster has a valid CSRF token, so a 403 here is authorization (site editor is ADMIN-only), not CSRF.
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/site", HttpMethod.PUT, session.write(SITE_PAYLOAD, true), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Logs in via the dev bypass and captures the session + CSRF cookies the chain set. */
    private Session devLogin(String role) {
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
    }

    /** A logged-in session's cookies; {@code MOSAICAST_SESSION=…} and {@code XSRF-TOKEN=…} pairs. */
    private record Session(String sessionCookie, String xsrfCookie) {

        /** A JSON write carrying both cookies, with the {@code X-XSRF-TOKEN} header only when {@code withCsrf}. */
        HttpEntity<String> write(String body, boolean withCsrf) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            if (withCsrf) {
                // The SPA echoes the XSRF-TOKEN cookie value back in the header (SecurityConfig SPA handler).
                headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            }
            return new HttpEntity<>(body, headers);
        }
    }
}
