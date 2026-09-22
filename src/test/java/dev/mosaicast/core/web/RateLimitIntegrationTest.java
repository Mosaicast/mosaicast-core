// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Rate limiting on auth endpoints and uploads (ARCHITECTURE §13), through the real filter chain.
 *
 * <p>The limits are set absurdly low here so the test does not have to send twenty requests to prove a
 * point. What matters is the shape: state-changing auth traffic is counted, reads are not, and a refusal is
 * an RFC 7807 429 carrying {@code Retry-After} — written by the filter itself, since it runs outside the
 * {@code @ControllerAdvice} that produces that shape everywhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(properties = {
        // The build turns rate limiting off for the suite (every test shares one client address); this is
        // the one class that wants it on. @TestPropertySource outranks the system property the build sets.
        "mosaicast.rate-limit.enabled=true",
        "mosaicast.rate-limit.auth-limit=3",
        "mosaicast.rate-limit.auth-window=5m",
        // Loopback is inside the shipped `internal-proxies` list — a proxy on the compose network has to
        // be trusted or a normal deployment breaks — so the test client would be believed. Narrowed here to
        // a range it is not in, which is the configuration of an app published directly, and the one the
        // forged-header case is about.
        "server.tomcat.remoteip.internal-proxies=10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}",
        "mosaicast.rate-limit.search-limit=3",
        "mosaicast.rate-limit.search-window=5m",
})
class RateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void authAttemptsAreRefusedOnceTheBudgetIsSpentAndSayWhenToRetry() {
        // Deliberately no CSRF token: the request is refused either way, and what is under test is that the
        // limiter runs *before* that work — a flood should not cost a session lookup, let alone a login.
        ResponseEntity<String> last = null;
        for (int i = 0; i < 6; i++) {
            last = post("/api/auth/dev-login?role=fan");
        }

        assertThat(last).isNotNull();
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(last.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(last.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(last.getBody()).contains("https://mosaicast.dev/problems/too-many-requests");
        assertThat(last.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        // Never "retry in 0 seconds" — a caller honouring that comes straight back and is refused again.
        assertThat(Integer.parseInt(last.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void ordinaryReadsAreNotCountedAgainstAnything() {
        // The budget above is 3 per five minutes and the previous test may already have spent it. Reads must
        // be unaffected regardless — limiting a GET would throttle the site itself, not an attacker.
        for (int i = 0; i < 25; i++) {
            assertThat(rest.getForEntity("/api/site", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void anUnlimitedMutationIsUntouched() {
        // Only the declared auth and upload paths are limited; every other endpoint keeps its own answer.
        // A 401/403 here is the endpoint's own refusal, and specifically *not* a 429.
        for (int i = 0; i < 12; i++) {
            assertThat(post("/api/admin/site").getStatusCode())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test
    void anAnonymousSearchIsBudgetedAlthoughItIsAGet() {
        // Every other budget here counts state-changing requests only, because a GET that costs the server
        // nothing should not punish someone clicking twice. Search is the exception: open to anyone, and the
        // most database-expensive read the site serves — a full-text query plus a separate count, both
        // re-tokenising the term (core#188).
        ResponseEntity<String> last = null;
        for (int i = 0; i < 4; i++) {
            last = rest.getForEntity("/api/search?q=kraken", String.class);
        }

        assertThat(last).isNotNull();
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aForgedForwardedHeaderNoLongerMintsAFreshBudget() {
        // The limiter keys on the client address, and `forward-headers-strategy: framework` let any caller
        // rewrite that with a header. Rotating it defeated the login and token budgets outright, and
        // pinning a victim's address at 429 was the same trick pointed the other way (core#173). Tomcat's
        // RemoteIpValve performs the same rewrite only for a peer inside `internal-proxies`, and the test
        // client is not one.
        for (int i = 0; i < 4; i++) {
            postAs("/api/auth/dev-login?role=fan", "203.0.113." + i);
        }

        assertThat(postAs("/api/auth/dev-login?role=fan", "198.51.100.7").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> postAs(String path, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Forwarded-For", forwardedFor);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    private ResponseEntity<String> post(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }
}
