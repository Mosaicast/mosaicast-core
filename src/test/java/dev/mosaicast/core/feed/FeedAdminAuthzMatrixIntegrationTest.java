// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins the authorization matrix of {@code /api/admin/feeds/**} (ARCHITECTURE §8.5): feeds are a
 * <strong>PODCASTER</strong> capability, on reads and writes alike, and everything below that is refused.
 *
 * <p>This exists because two security audits reported the opposite and were both wrong in the same way.
 * They observed {@code GET /api/admin/feeds} returning 200 to a podcaster and every write returning 403, and
 * concluded the read path was missing a role check. There is no such gap: {@code SecurityConfig} grants
 * {@code hasAnyRole("ADMIN","PODCASTER")} on the whole path for every method, and no method-level gate
 * narrows it. The 403s came from a CSRF token captured before {@code dev-login} and reused after — a
 * mistake that is easy to make and impossible to distinguish from an authorization failure by status code
 * alone.
 *
 * <p>So this test takes a <strong>fresh token per session</strong> and asserts the matrix directly. A
 * measurement is worth more than a paragraph, and if someone later widens or narrows these rules by
 * accident, this fails rather than a reviewer noticing.
 *
 * <p>Note what an authorized caller is asserted to get: <em>not</em> 401 or 403. Whether that is a 200, a
 * 404 for an id that does not exist, or a 400 because the outbound filter refused the URL is this test's
 * business only insofar as it proves the request got past the filter chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class FeedAdminAuthzMatrixIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** A private address, so preview/create are refused by the egress filter rather than reaching a host. */
    private static final String BLOCKED_URL = "{\"url\":\"http://127.0.0.1:9/feed.xml\"}";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void anonymousGetsNothingAtAll() {
        // With a token, so every refusal here is authorization and nothing else.
        String token = DevLogin.token(rest);
        for (Endpoint endpoint : endpoints()) {
            assertThat(call(endpoint, null, token).getStatusCode())
                    .as("anonymous %s", endpoint)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void anUnsafeMethodWithNoTokenIsRefusedByCsrfBeforeAuthorizationIsConsulted() {
        // The trap, asserted so it stops being a trap. Without a token an unsafe method is 403 — not
        // because of the caller's role, but because CSRF runs first and never reaches the authorization
        // filter. Read as an authorization result it says "this role may not write", which is what the
        // audits concluded about podcasters. The same request as a GET, which CSRF does not guard, is 401.
        assertThat(call(new Endpoint(HttpMethod.POST, "/api/admin/feeds/preview", BLOCKED_URL), null, null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(new Endpoint(HttpMethod.GET, "/api/admin/feeds", null), null, null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aFanIsRefusedEverywhereReadsIncluded() {
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");
        for (Endpoint endpoint : endpoints()) {
            assertThat(call(endpoint, fan).getStatusCode())
                    .as("fan %s", endpoint)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void aPodcasterReachesEveryFeedEndpointIncludingTheWrites() {
        // The finding this corrects. Feeds, planned episodes and the preview that triggers an outbound fetch
        // are all a podcaster's job (§8.5) — the role an admin promoted someone into precisely so they could
        // manage their own show. `preview` is the one worth naming: it dereferences a URL server-side and
        // reads the channel back, which is why OutboundTargetPolicy exists and why a podcaster account is a
        // meaningful thing to protect.
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        for (Endpoint endpoint : endpoints()) {
            assertThat(call(endpoint, podcaster).getStatusCode())
                    .as("podcaster %s", endpoint)
                    .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void anAdminReachesEveryFeedEndpointToo() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        for (Endpoint endpoint : endpoints()) {
            assertThat(call(endpoint, admin).getStatusCode())
                    .as("admin %s", endpoint)
                    .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void theSiblingAdminEndpointsStayAdminOnly() {
        // The contrast that makes the matrix above a decision rather than an oversight: everything else
        // under /api/admin is ADMIN, and a podcaster reaching feeds does not reach users or site config.
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        for (String path : new String[] {"/api/admin/users", "/api/admin/plugins", "/api/admin/consent"}) {
            assertThat(rest.exchange(path, HttpMethod.GET, read(podcaster), String.class).getStatusCode())
                    .as("podcaster GET %s", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    private static Endpoint[] endpoints() {
        String id = UUID.randomUUID().toString();
        return new Endpoint[] {
            new Endpoint(HttpMethod.GET, "/api/admin/feeds", null),
            new Endpoint(HttpMethod.GET, "/api/admin/feeds/" + id, null),
            new Endpoint(HttpMethod.POST, "/api/admin/feeds/preview", BLOCKED_URL),
            new Endpoint(HttpMethod.POST, "/api/admin/feeds", BLOCKED_URL),
            new Endpoint(HttpMethod.POST, "/api/admin/feeds/" + id + "/refresh", ""),
            new Endpoint(HttpMethod.POST, "/api/admin/feeds/" + id + "/enabled?value=false", ""),
            new Endpoint(HttpMethod.POST, "/api/admin/feeds/" + id + "/planned-episodes",
                    "{\"season\":1,\"episodeNo\":1,\"title\":\"Planned\"}"),
            new Endpoint(HttpMethod.GET, "/api/admin/feeds/" + id + "/suggestions", null),
        };
    }

    private ResponseEntity<String> call(Endpoint endpoint, DevLogin.Cookies session) {
        return call(endpoint, session, null);
    }

    /**
     * Issues one request. A session carries its own token; {@code anonymousToken} supplies one without a
     * session, so an anonymous refusal is authorization rather than CSRF.
     */
    private ResponseEntity<String> call(Endpoint endpoint, DevLogin.Cookies session, String anonymousToken) {
        HttpHeaders headers = new HttpHeaders();
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
            headers.add("X-XSRF-TOKEN", session.token());
        } else if (anonymousToken != null) {
            headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + anonymousToken);
            headers.add("X-XSRF-TOKEN", anonymousToken);
        }
        HttpEntity<String> request;
        if (endpoint.body() == null) {
            request = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            request = new HttpEntity<>(endpoint.body(), headers);
        }
        return rest.exchange(endpoint.path(), endpoint.method(), request, String.class);
    }

    private static HttpEntity<Void> read(DevLogin.Cookies session) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session.session());
        return new HttpEntity<>(headers);
    }

    /** One cell of the matrix: a request shape, independent of who makes it. */
    private record Endpoint(HttpMethod method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }
}
