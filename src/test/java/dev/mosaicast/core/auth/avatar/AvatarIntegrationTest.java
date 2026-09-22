// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.auth.User;
import dev.mosaicast.core.auth.UserRepository;
import dev.mosaicast.core.support.DevLogin;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The avatar endpoint (ARCHITECTURE §8.7) through the real chain.
 *
 * <p>The assertions worth reading are the negative ones. §8.7 exists because the old arrangement leaked the
 * Discord snowflake through a URL the browser fetched, so what is tested here is mostly that the host never
 * hands one out: it answers bytes, never a redirect, and never mentions the CDN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class AvatarIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkedIdentityRepository identities;

    private UUID fanId;

    @BeforeEach
    void signIn() {
        devLogin("fan");
        fanId = identities.findByProviderAndExternalId("dev", "FAN").orElseThrow().getUserId();
    }

    @Test
    void answersAGeneratedPictureForAUserWithNoProviderSource() {
        ResponseEntity<String> response = rest.getForEntity("/api/users/" + fanId + "/avatar", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("<svg");
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.toString()).startsWith("image/svg+xml"));
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }

    @Test
    void neverRedirectsAndNeverNamesTheProvidersCdn() {
        // The point of §8.7. A 302 would put the Discord snowflake into the page and hand the CDN a hit
        // from every visitor's browser — exactly what proxying the bytes exists to prevent.
        ResponseEntity<String> response = rest.getForEntity("/api/users/" + fanId + "/avatar", String.class);

        assertThat(response.getStatusCode().is3xxRedirection()).isFalse();
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getBody()).doesNotContain("discordapp");
    }

    @Test
    void isPublicBecauseItServesPixelsRatherThanIdentifiers() {
        // No session: a leaderboard rendered for an anonymous visitor still needs the pictures, and this
        // endpoint discloses only a picture for a user id the caller already had.
        ResponseEntity<String> response = rest.getForEntity("/api/users/" + fanId + "/avatar", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void revalidatesWithAnEtagRatherThanResending() {
        String etag = rest.getForEntity("/api/users/" + fanId + "/avatar", String.class)
                .getHeaders().getETag();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);
        ResponseEntity<String> second = rest.exchange("/api/users/" + fanId + "/avatar",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // What makes holding the cache only in memory affordable: a cold start costs revalidations.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void anUnknownUserIsANotFoundRatherThanABlankImage() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/users/" + UUID.randomUUID() + "/avatar", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void choosingASourceWithNoPictureIsRefusedRatherThanStoredSilently() {
        Session fan = devLogin("fan");
        // The dev identity has no avatar_ref. Storing this would be a setting that does nothing, and the
        // user would have no way to tell that from a bug.
        ResponseEntity<String> response = rest.exchange("/api/me/avatar", HttpMethod.PUT,
                fan.write("{\"provider\":\"dev\"}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(users.findById(fanId).orElseThrow().getAvatarProvider()).isNull();
    }

    @Test
    void choosingAnUnlinkedProviderIsRefused() {
        Session fan = devLogin("fan");
        ResponseEntity<String> response = rest.exchange("/api/me/avatar", HttpMethod.PUT,
                fan.write("{\"provider\":\"discord\"}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void selectingTheGeneratedPictureIsAlwaysAllowed() {
        Session fan = devLogin("fan");
        ResponseEntity<String> response = rest.exchange("/api/me/avatar", HttpMethod.PUT,
                fan.write("{\"provider\":null}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        User fanUser = users.findById(fanId).orElseThrow();
        assertThat(fanUser.getAvatarProvider()).isNull();
        // The profile carries the host's own URL, never a provider's.
        assertThat(response.getBody()).contains("/api/users/" + fanId + "/avatar");
    }

    @Test
    void theProfileNeverCarriesAProviderUrl() {
        Session fan = devLogin("fan");
        ResponseEntity<String> me = rest.exchange("/api/me", HttpMethod.GET, fan.read(), String.class);

        assertThat(me.getBody())
                .contains("\"avatarUrl\":\"/api/users/" + fanId + "/avatar\"")
                .doesNotContain("cdn.discordapp.com");
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

        HttpEntity<String> write(String body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1).split(";")[0]);
            return new HttpEntity<>(body, headers);
        }
    }
}
