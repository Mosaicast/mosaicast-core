// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end plugin loading (ARCHITECTURE §7). Boots the host against a real staged plugins dir holding a
 * good fixture plugin plus two deliberately-broken ones (incompatible {@code platformApi}; declared schema).
 * Asserts the good plugin loads and its {@code register(ctx)} ran (its seeded doc round-trips through the
 * HTTP surface, an asset serves), the broken ones are rejected-with-reason while the host still booted
 * (failure isolation, §7.8), and the doc surface enforces read/write access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class PluginLoadingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void goodPluginIsInPublicManifestAndBrokenOnesAreNot() {
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manifest.getBody()).contains("\"id\":\"good\"");
        assertThat(manifest.getBody()).doesNotContain("\"broken\"").doesNotContain("\"schema\"");
    }

    @Test
    void adminSeesLoadStateWithReasons() {
        Session admin = devLogin("admin");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/plugins", HttpMethod.GET, admin.get(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"id\":\"good\"").contains("LOADED");
        assertThat(body).contains("REJECTED");
        // The two rejections carry a reason each (platformApi mismatch / schema storage).
        assertThat(body).containsIgnoringCase("platformApi");
        assertThat(body).containsIgnoringCase("schema");
    }

    @Test
    void registerRanAndSeededDocStore() {
        // FixturePlugin.register put a site-scoped greeting + episode-count; both read back publicly.
        ResponseEntity<String> greeting =
                rest.getForEntity("/api/plugins/good/data/site/main/greeting", String.class);
        assertThat(greeting.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(greeting.getBody()).contains("hello from fixture");

        ResponseEntity<String> count =
                rest.getForEntity("/api/plugins/good/data/site/main/episode-count", String.class);
        assertThat(count.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count.getBody().trim()).isEqualTo("0");
    }

    @Test
    void missingDocIs404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/good/data/site/main/absent", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assetIsServed() {
        ResponseEntity<String> response =
                rest.getForEntity("/plugins/good/assets/fixture.js", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("export const fixture");
    }

    @Test
    void scopeEpisodesResolvePublicly() {
        // The shell reads this to fill ctx.episodes; site scope resolves to a (possibly empty) JSON array.
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/scope-episodes?type=site&id=main", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[");
    }

    @Test
    void unknownPluginIs404() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/nope/data/site/main/x", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void writeRequiresAuthAndRole() {
        String path = "/api/plugins/good/data/episode/ep-1/note";
        String body = "{\"text\":\"a highlight\"}";

        // Anonymous write is refused: a cookie-less, non-bearer PUT fails CSRF (403) before it could reach
        // a handler — either way, no anonymous writes.
        ResponseEntity<String> anon = rest.exchange(
                path, HttpMethod.PUT, json(body), String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A podcaster (the write floor) succeeds, and the note reads back publicly.
        Session podcaster = devLogin("podcaster");
        ResponseEntity<String> write = rest.exchange(
                path, HttpMethod.PUT, podcaster.write(body, true), String.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> read = rest.getForEntity(path, String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).contains("a highlight");

        // Delete is idempotent.
        assertThat(rest.exchange(path, HttpMethod.DELETE, podcaster.delete(true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(path, HttpMethod.DELETE, podcaster.delete(true), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Session devLogin(String role) {
        ResponseEntity<String> response =
                rest.postForEntity("/api/auth/dev-login?role=" + role, null, String.class);
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

    /** A logged-in session's cookies with helpers to build authenticated requests. */
    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<Void> get() {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            return new HttpEntity<>(headers);
        }

        HttpEntity<String> write(String body, boolean withCsrf) {
            HttpHeaders headers = baseHeaders(withCsrf);
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(body, headers);
        }

        HttpEntity<Void> delete(boolean withCsrf) {
            return new HttpEntity<>(baseHeaders(withCsrf));
        }

        private HttpHeaders baseHeaders(boolean withCsrf) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            if (withCsrf) {
                headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1));
            }
            return headers;
        }
    }
}
