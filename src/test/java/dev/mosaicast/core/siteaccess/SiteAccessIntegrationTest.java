// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The site-access policy over HTTP (ARCHITECTURE §18.2): what the edge reads, who may change it, and the
 * guarantees the edge builds on — no row means open, a too-close opening is refused, an unchanged poll is a
 * 304, and only a poll with the shared token counts as the edge reporting in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(properties = "mosaicast.edge.token=edge-secret")
class SiteAccessIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SiteAccessRepository repository;

    @Autowired
    private ObjectMapper json;

    @AfterEach
    void reopen() {
        repository.deleteAll();
    }

    @Test
    void anInstallThatNeverSavedAPolicyIsOpenToAnonymousReaders() {
        ResponseEntity<String> response = rest.getForEntity("/api/site/access", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("state").asString()).isEqualTo("open");
        assertThat(body.get("effectiveState").asString()).isEqualTo("open");
        assertThat(body.get("passFloor").asString()).isEqualTo("podcaster");
        assertThat(body.get("staffLogin").get("visible").asBoolean()).isFalse();
        assertThat(body.get("revision").asLong()).isZero();
    }

    @Test
    void anAdminSchedulesTheOpeningAndThePublicReadShowsIt() {
        Instant opens = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        ResponseEntity<String> saved = put(DevLogin.login(rest, "admin"),
                "{\"state\":\"scheduled\",\"opensAt\":\"" + opens + "\",\"passFloor\":\"podcaster\","
                        + "\"staffLoginVisible\":true,\"signupsWhileClosed\":false}");
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = json.readTree(rest.getForEntity("/api/site/access", String.class).getBody());
        assertThat(body.get("state").asString()).isEqualTo("scheduled");
        assertThat(body.get("effectiveState").asString()).isEqualTo("scheduled");
        assertThat(Instant.parse(body.get("opensAt").asString())).isEqualTo(opens);
        assertThat(body.get("staffLogin").get("visible").asBoolean()).isTrue();
        assertThat(body.get("revision").asLong()).isEqualTo(1);
    }

    @Test
    void anOpeningTooCloseToNowIsRefusedWithACode() {
        Instant soon = Instant.now().plusSeconds(20);
        ResponseEntity<String> response = put(DevLogin.login(rest, "admin"),
                "{\"state\":\"scheduled\",\"opensAt\":\"" + soon + "\",\"passFloor\":\"podcaster\","
                        + "\"staffLoginVisible\":false,\"signupsWhileClosed\":false}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("siteAccess.opensAt.tooSoon");
        assertThat(repository.count()).isZero();
    }

    @Test
    void openNowSchedulesTheOpeningAFewSecondsOut() {
        Instant before = Instant.now();
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        ResponseEntity<String> response = rest.exchange("/api/admin/site/access/open-now", HttpMethod.POST,
                write(admin, "{}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode access = json.readTree(response.getBody()).get("access");
        assertThat(access.get("state").asString()).isEqualTo("scheduled");
        Instant opens = Instant.parse(access.get("opensAt").asString());
        assertThat(opens).isBetween(before.plusSeconds(11), Instant.now().plusSeconds(13));
    }

    @Test
    void anUnchangedPolicyAnswersAPollWithNotModified() {
        ResponseEntity<String> first = rest.getForEntity("/api/site/access", String.class);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);
        ResponseEntity<String> again = rest.exchange("/api/site/access", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void onlyAnAdminMayChangeThePolicy() {
        String body = "{\"state\":\"closed\",\"passFloor\":\"podcaster\",\"staffLoginVisible\":false,"
                + "\"signupsWhileClosed\":false}";
        assertThat(put(DevLogin.login(rest, "podcaster"), body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(DevLogin.login(rest, "fan"), body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/api/admin/site/access", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(repository.count()).isZero();
    }

    @Test
    void onlyAPollWithTheSharedTokenCountsAsTheEdgeReportingIn() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        HttpHeaders forged = new HttpHeaders();
        forged.add(SiteAccessController.TOKEN_HEADER, "guess");
        forged.add(SiteAccessController.BUILD_HEADER, "forged");
        rest.exchange("/api/site/access", HttpMethod.GET, new HttpEntity<>(forged), String.class);
        JsonNode afterForgery = edge(admin);
        assertThat(afterForgery.path("status").asString()).isNotEqualTo("connected");
        assertThat(afterForgery.path("build").isMissingNode() || afterForgery.path("build").isNull()).isTrue();

        HttpHeaders real = new HttpHeaders();
        real.add(SiteAccessController.TOKEN_HEADER, "edge-secret");
        real.add(SiteAccessController.BUILD_HEADER, "abc123");
        rest.exchange("/api/site/access", HttpMethod.GET, new HttpEntity<>(real), String.class);
        JsonNode edge = edge(admin);
        assertThat(edge.get("status").asString()).isEqualTo("connected");
        assertThat(edge.get("build").asString()).isEqualTo("abc123");
    }

    @Test
    void theSessionCookieHasTheShapeTheEdgeChecksBeforeAskingCore() {
        // The edge treats any other shape as anonymous without calling /api/me, so random cookies cannot be
        // used to load core (§18.4). Changing the session id or its encoding breaks that check: pinned here.
        String pair = DevLogin.login(rest, "fan").session();
        String value = pair.substring(pair.indexOf('=') + 1);

        assertThat(value).hasSize(48);
        String decoded = new String(java.util.Base64.getDecoder().decode(value),
                java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(decoded).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private JsonNode edge(DevLogin.Cookies admin) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, admin.session());
        ResponseEntity<String> response = rest.exchange("/api/admin/site/access", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody()).get("edge");
    }

    private ResponseEntity<String> put(DevLogin.Cookies who, String body) {
        return rest.exchange("/api/admin/site/access", HttpMethod.PUT, write(who, body), String.class);
    }

    private static HttpEntity<String> write(DevLogin.Cookies who, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return new HttpEntity<>(body, headers);
    }
}
