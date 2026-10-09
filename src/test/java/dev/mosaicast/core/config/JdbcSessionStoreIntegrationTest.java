// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code MOSAICAST_SESSION_STORE=jdbc} (ARCHITECTURE §8.5): a sign-in is kept in Postgres, with everything it
 * carries serialisable, so a restart does not sign anyone out. The restart is stood in for by a second
 * repository over the same tables, the way a new process would read them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mosaicast.session.store=jdbc")
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class JdbcSessionStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aSignInIsStoredInPostgresAndReadableByAFreshProcess() {
        DevLogin.Cookies login = DevLogin.login(rest, "podcaster");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, login.session() + "; " + login.xsrf());

        ResponseEntity<String> me = rest.exchange("/api/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("\"role\":\"podcaster\"");

        String sessionId = new String(Base64.getDecoder().decode(
                login.session().substring(login.session().indexOf('=') + 1)), StandardCharsets.UTF_8);
        Long rows = jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?", Long.class,
                sessionId);
        assertThat(rows).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo((int) Duration.ofDays(30).toSeconds());

        // What a restarted process sees: the same rows, read by a repository that never held them in memory.
        JdbcIndexedSessionRepository fresh =
                new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
        Session restored = fresh.findById(sessionId);
        assertThat(restored).isNotNull();
        SecurityContext context = restored.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getAuthorities())
                .anySatisfy(authority -> assertThat(authority.getAuthority()).isEqualTo("ROLE_PODCASTER"));
    }
}
