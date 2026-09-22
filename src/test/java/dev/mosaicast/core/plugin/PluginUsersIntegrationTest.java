// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The plugin user directory end to end (ARCHITECTURE §8.8).
 *
 * <p>Three fixtures carry the cases: {@code directory} declares an {@code identity} block at an anonymous
 * read floor, {@code directorylocked} declares the same behind a podcaster one, and every other fixture
 * declares none — which is a 404 rather than a 403, the same answer {@code blobs} and {@code tags} give a
 * plugin that never asked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginUsersIntegrationTest {

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
    private UserRepository users;

    @Autowired
    private LinkedIdentityRepository identities;

    private UUID fanId;

    @BeforeEach
    void aUserToResolve() {
        DevLogin.login(rest, "fan");
        fanId = identities.findByProviderAndExternalId("dev", "FAN").orElseThrow().getUserId();
    }

    @Test
    void resolvesIdsToNamesAndHostRelativeAvatars() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/plugins/directory/users?ids=" + fanId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"id\":\"" + fanId + "\"")
                .contains("\"avatarUrl\":\"/api/users/" + fanId + "/avatar\"")
                .contains("\"role\":");
    }

    @Test
    void neverDisclosesEmailProviderOrExternalId() {
        // The whole of what a plugin may learn about somebody else is UserRef (§8.8). The login key is
        // (provider, external_id) (§8.2), and publishing it is what social login exists to avoid.
        ResponseEntity<String> response = rest.getForEntity(
                "/api/plugins/directory/users?ids=" + fanId, String.class);

        assertThat(response.getBody())
                .doesNotContain("email")
                .doesNotContain("provider")
                .doesNotContain("externalId")
                .doesNotContain("displayKey");
    }

    @Test
    void anUnknownIdIsAbsentRatherThanRedacted() {
        UUID gone = UUID.randomUUID();
        ResponseEntity<String> response = rest.getForEntity(
                "/api/plugins/directory/users?ids=" + fanId + "," + gone, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Unknown, erased and pseudonymised produce the same missing row, so the answer cannot be used to
        // work out which of the three a given id is (§12.8).
        assertThat(response.getBody()).contains(fanId.toString()).doesNotContain(gone.toString());
    }

    @Test
    void aMalformedIdIsSkippedRatherThanRefused() {
        // A caller that can tell "malformed" from "no such user" learns something about ids it guessed.
        ResponseEntity<String> response = rest.getForEntity(
                "/api/plugins/directory/users?ids=not-a-uuid," + fanId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(fanId.toString());
    }

    @Test
    void anEmptyRequestResolvesToAnEmptyList() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/plugins/directory/users", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void aReadFloorAboveAnonymousKeepsTheRolesOutOfAnonymousReach() {
        // `GET /api/plugins/{id}/users` sat under the permitAll rule and checked no read floor, unlike
        // every other plugin surface. The class comment reasoned "nothing here a visitor could not already
        // see" — true of a name and an avatar, and not of the role: a UserRef carries it, and core
        // publishes no other anonymous surface that says who the admins are (core#180).
        assertThat(rest.getForEntity("/api/plugins/directorylocked/users?ids=" + fanId, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The same plugin, a caller who meets its floor.
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, podcaster.session() + "; " + podcaster.xsrf());
        ResponseEntity<String> allowed = rest.exchange(
                "/api/plugins/directorylocked/users?ids=" + fanId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).contains(fanId.toString());
    }

    @Test
    void aPluginWithNoIdentityBlockHasNoSurfaceAtAll() {
        // 404, not 403: indistinguishable from an unknown plugin, so a page cannot probe which plugins on
        // this install declared the directory.
        assertThat(rest.getForEntity("/api/plugins/good/users?ids=" + fanId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/plugins/nosuch/users?ids=" + fanId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void isReadableAnonymouslyBecauseALeaderboardIs() {
        // No session: the names and pictures on a leaderboard are shown to whoever can see the leaderboard,
        // so a read floor here would break the surface it exists for without protecting anything.
        ResponseEntity<String> response = rest.getForEntity(
                "/api/plugins/directory/users?ids=" + fanId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theManifestTellsTheShellWhichPluginsMayResolve() {
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);
        assertThat(manifest.getBody()).contains("\"hasIdentity\":true").contains("\"hasIdentity\":false");
    }
}
