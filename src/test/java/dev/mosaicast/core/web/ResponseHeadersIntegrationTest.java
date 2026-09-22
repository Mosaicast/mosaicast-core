// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The response headers the framework supplies, and the ones it does not (ARCHITECTURE §13).
 *
 * <p>The CSP has nine tests of its own; these had none. {@code X-Content-Type-Options},
 * {@code X-Frame-Options}, {@code Cache-Control} and {@code Referrer-Policy} come from Spring Security's
 * defaults, so a future {@code defaultsDisabled()} would remove them in silence — and four more were
 * simply absent, because none of them is a default (core#187).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class ResponseHeadersIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theFrameworkDefaultsArePinned() {
        HttpHeaders headers = rest.getForEntity("/api/meta", String.class).getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        // An API response is not cacheable, and this is the default that the asset rule below must not
        // have switched off globally.
        assertThat(headers.getCacheControl()).contains("no-store");
    }

    @Test
    void theHeadersThatAreNotDefaultsArePresent() {
        HttpHeaders headers = rest.getForEntity("/api/meta", String.class).getHeaders();

        assertThat(headers.getFirst("Permissions-Policy"))
                .contains("camera=()").contains("geolocation=()").contains("microphone=()");
        assertThat(headers.getFirst("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-site");
    }

    @Test
    void theContentSecurityPolicyBoundsFormsAndTheBaseUrl() {
        String csp = rest.getForEntity("/api/meta", String.class)
                .getHeaders().getFirst("Content-Security-Policy");

        // Neither falls back to default-src, so their absence means the policy says nothing about them.
        assertThat(csp).contains("form-action 'self'").contains("base-uri 'self'");
        assertThat(csp).contains("object-src 'none'").contains("frame-ancestors 'none'");
    }

    @Test
    void aRefusalFromTheFilterChainIsAProblemDocument() {
        // Application errors have always been problem+json; errors raised in the filter chain fell through
        // to Spring Boot's default page, so a client had two shapes to parse — and the second one names
        // the framework and the path.
        ResponseEntity<String> anonymous = rest.getForEntity("/api/me", String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getHeaders().getContentType())
                .isNotNull()
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(anonymous.getBody())
                .contains("\"status\":401")
                .contains("mosaicast.dev/problems/unauthorized")
                .doesNotContain("\"error\"")
                .doesNotContain("\"path\"");
    }

    @Test
    void theActuatorIsNotPublicBeyondItsProbes() {
        // Deny-by-default ended at /api/**: the catch-all `anyRequest().permitAll()` answered for
        // /actuator/**, so the only thing keeping env/configprops/loggers off the internet was an exposure
        // property in a different file.
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/actuator/configprops", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
