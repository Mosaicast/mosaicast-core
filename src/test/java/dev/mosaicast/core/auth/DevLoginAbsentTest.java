// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.ResponseEntity;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Security guard (dev-login conditions, §13.5): the dev-login bypass must be <strong>structurally absent
 * when the {@code dev} profile is not active</strong>. This test runs with the default profile, so the
 * bean is never registered. The request is sent with a valid CSRF token so it reaches dispatch and proves
 * the handler is missing → 404, rather than being turned away at the front for the wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class DevLoginAbsentTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void devLoginEndpointIsAbsentWithoutDevProfile() {
        // Obtain a CSRF token from a public GET (the CsrfCookieFilter sets XSRF-TOKEN on the response).
        ResponseEntity<String> primer = rest.getForEntity("/api/meta", String.class);
        String token = cookieValue(primer.getHeaders(), "XSRF-TOKEN");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);

        ResponseEntity<String> response = rest.exchange(
                "/api/auth/dev-login?role=admin", HttpMethod.POST, new HttpEntity<>(headers), String.class);

        // CSRF satisfied → reaches the dispatcher → no handler under the default profile → 404.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static String cookieValue(HttpHeaders headers, String name) {
        return headers.get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring((name + "=").length(), c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + name + " cookie set"));
    }
}
