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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the SPA fallback answers for a path no controller claims (ARCHITECTURE §6.6).
 *
 * <p>Every unmatched path used to answer <strong>200</strong> with the shell — a soft 404, which a crawler
 * indexes as a valid page, and which the episode route has never done (core#179). The shell is still
 * served, because the SPA's own not-found view is a better page than a bare error; what changes is the
 * status line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class SpaFallbackIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void anUnknownTopLevelPathIs404AndStillRendersTheShell() {
        ResponseEntity<String> response = rest.getForEntity("/totally/unknown/route", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("<div id=\"root\">");
    }

    @Test
    void aClientSideRouteTheShellClaimsIsStill200() {
        // The whole risk of deciding this server-side: a route the router owns must not start 404ing
        // because this list forgot it. SpaRoutesMatchTheRouterTest is the guard; these are the ones a
        // visitor reaches most.
        for (String path : new String[] {"/search", "/about", "/cookies", "/notifications", "/account"}) {
            assertThat(rest.getForEntity(path, String.class).getStatusCode())
                    .describedAs(path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void theRootIsStill200() {
        assertThat(rest.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMissingStaticFileIsNotMarkedByThisRule() {
        // A path with an extension is a file, and its status is the resource handler's to decide. Marking
        // it here would give a file that *does* exist the wrong status.
        assertThat(rest.getForEntity("/brand/mosaicast-mark.svg", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
