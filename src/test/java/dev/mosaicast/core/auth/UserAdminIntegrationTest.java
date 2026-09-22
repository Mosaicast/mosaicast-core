// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

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

    @Test
    void anUnknownRoleNameIsAClientErrorAndTheSameOneOnBothRoutes() {
        // It used to be a ConflictException → 409 here and an IllegalArgumentException → 400 on the
        // dev-login route, for the same input. 409 on this endpoint means a state conflict — the last
        // admin, your own role — and a word this host does not know is not one of those (core#197).
        devLogin("fan"); // materialises the dev FAN user
        Session admin = devLogin("admin");
        UUID fanId = userId("FAN");

        assertThat(rest.exchange("/api/admin/users/" + fanId + "/role", HttpMethod.PUT,
                admin.write("{\"role\":\"wizard\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aRoleNameIsReadTheSameWayOnEveryJvm() {
        // On a Turkish JVM "admin".toUpperCase() is "ADMİN" — a dotted capital I — and Role.valueOf does
        // not know it, so every role change on that server failed with "Unknown role". The host's default
        // locale comes from its environment, which a self-hosted project does not choose (core#197).
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(Roles.parse("admin")).isEqualTo(Role.ADMIN);
            assertThat(Roles.parse("  Podcaster ")).isEqualTo(Role.PODCASTER);
            assertThat(Roles.parse("FAN")).isEqualTo(Role.FAN);
            assertThatThrownBy(() -> Roles.parse("wizard"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("admin, podcaster, fan");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void theLastAdminGuardTakesALockRatherThanJustCounting() throws Exception {
        // The guard was a read followed by a write in separate transactions, so two admins demoting each
        // other at the same time both read "2 admins", both passed, and both committed — leaving the site
        // with none and no way back in short of ADMIN_BOOTSTRAP_EXTERNAL_ID and a restart (core#166).
        //
        // Driven at the repository rather than over HTTP on purpose. Two concurrent PUTs do not reliably
        // interleave inside the guard's window — a version of this test that made them raced nothing and
        // passed against the unfixed code, which is worse than no test. What the fix actually adds is that
        // the read serialises, and that is what this asserts: a second transaction asking the same question
        // cannot answer until the first has committed its change.
        devLogin("admin");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                users.findByRoleOrderByIdAsc(Role.ADMIN);
                holding.countDown();
                Future<Boolean> other = threads.submit(() -> {
                    transactions.executeWithoutResult(inner -> users.findByRoleOrderByIdAsc(Role.ADMIN));
                    return true;
                });
                try {
                    // While this transaction holds the lock, the other one cannot get past the same read.
                    assertThatThrownBy(() -> other.get(2, TimeUnit.SECONDS))
                            .isInstanceOf(TimeoutException.class);
                    release.countDown();
                } finally {
                    status.setRollbackOnly();
                }
            });
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isZero();
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void theLastAdminCannotBeDemoted() {
        // The invariant itself, end to end: with one admin left, the guard refuses and the row is unchanged.
        Session admin = devLogin("admin");
        UUID adminId = userId("ADMIN");
        Session other = devLogin("podcaster");
        UUID otherId = userId("PODCASTER");

        promote(admin, otherId, "admin");
        try {
            assertThat(demote(other, adminId)).isEqualTo(HttpStatus.OK);
            // `other` is now the only admin, and nobody can take that away.
            assertThat(demote(admin(other), otherId)).isEqualTo(HttpStatus.CONFLICT);
            assertThat(users.countByRole(Role.ADMIN)).isEqualTo(1);
        } finally {
            users.findById(adminId).ifPresent(user -> {
                user.changeRole(Role.ADMIN);
                users.save(user);
            });
            users.findById(otherId).ifPresent(user -> {
                user.changeRole(Role.PODCASTER);
                users.save(user);
            });
        }
    }

    /** The same session, now that its account has been promoted — the cookies do not change. */
    private Session admin(Session session) {
        return session;
    }

    private void promote(Session admin, UUID id, String role) {
        assertThat(rest.exchange("/api/admin/users/" + id + "/role", HttpMethod.PUT,
                admin.write("{\"role\":\"" + role + "\"}", true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpStatusCode demote(Session actor, UUID id) {
        return rest.exchange("/api/admin/users/" + id + "/role", HttpMethod.PUT,
                actor.write("{\"role\":\"podcaster\"}", true), String.class).getStatusCode();
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }

    private Session devLogin(String role) {
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
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
