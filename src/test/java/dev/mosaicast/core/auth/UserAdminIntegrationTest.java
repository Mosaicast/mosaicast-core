// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.UUID;
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
 * Admin user management through the real security + CSRF chain on the {@code dev} profile (ARCHITECTURE
 * §8.5): an admin can list users and change a role; a podcaster cannot reach the endpoint (RBAC); and the
 * guard rejects an admin changing their own role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class UserAdminIntegrationTest {

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
    void adminListsUsersAndPromotesAFan() {
        devLogin("fan"); // materialises the dev FAN user
        Session admin = devLogin("admin");

        ResponseEntity<String> list = rest.exchange(
                "/api/admin/users", HttpMethod.GET, admin.read(), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains("\"role\":\"admin\"").contains("\"role\":\"fan\"");

        UUID fanId = userId("FAN");
        ResponseEntity<String> change = rest.exchange(
                "/api/admin/users/" + fanId + "/role", HttpMethod.PUT,
                admin.write("{\"role\":\"podcaster\"}", true), String.class);
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(users.findById(fanId).orElseThrow().getRole()).isEqualTo(Role.PODCASTER);
    }

    @Test
    void adminCannotChangeTheirOwnRole() {
        Session admin = devLogin("admin");
        UUID adminId = userId("ADMIN");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users/" + adminId + "/role", HttpMethod.PUT,
                admin.write("{\"role\":\"fan\"}", true), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(users.findById(adminId).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void podcasterCannotListUsers() {
        Session podcaster = devLogin("podcaster");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users", HttpMethod.GET, podcaster.read(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }

    private Session devLogin(String role) {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/dev-login?role=" + role, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        return new Session(cookieValue(setCookies, "MOSAICAST_SESSION"), cookieValue(setCookies, "XSRF-TOKEN"));
    }

    private static String cookieValue(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(0, c.indexOf(';')))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + name + " cookie set"));
    }

    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<Void> read() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie);
            return new HttpEntity<>(headers);
        }

        HttpEntity<String> write(String body, boolean withCsrf) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            if (withCsrf) {
                headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            }
            return new HttpEntity<>(body, headers);
        }
    }
}
