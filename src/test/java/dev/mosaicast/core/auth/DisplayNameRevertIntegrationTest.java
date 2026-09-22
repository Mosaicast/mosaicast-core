// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.time.Instant;
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
 * Admin name revert (ARCHITECTURE §8.6.1) through the real security chain: the walk back, the oscillation
 * guard that makes a second revert go further rather than bouncing, the generated floor, the freeze, and
 * the fact that a podcaster cannot do any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class DisplayNameRevertIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserNameHistoryRepository history;

    @Autowired
    private LinkedIdentityRepository identities;

    private Session admin;
    private UUID fanId;

    /**
     * Puts every dev user back to its own distinct name with no history and no freeze.
     *
     * <p>All three, not just the fan: one test parks {@code Dev Fan} on the podcaster to prove a revert
     * will not collide with it, and names are unique now — so resetting only the fan makes the *next*
     * test fail on a constraint, in a class that never mentions the podcaster.
     */
    @BeforeEach
    void reset() {
        admin = devLogin("admin");
        devLogin("fan");
        devLogin("podcaster");
        fanId = userId("FAN");
        String[] roles = {"FAN", "PODCASTER", "ADMIN"};

        // Two phases, because the names are unique: a previous test may have parked "Dev Fan" on the
        // podcaster, and assigning the fan its name first would then collide with a row this same loop is
        // about to rewrite. The generated names are unique by construction, so they are a safe way station.
        for (String role : roles) {
            User user = users.findById(userId(role)).orElseThrow();
            String parking = DisplayNames.generatedFor(user.getId());
            user.rename(parking, DisplayNames.canonicalise(parking));
            user.lockRenameUntil(null);
            users.saveAndFlush(user);
        }
        for (String role : roles) {
            UUID id = userId(role);
            User user = users.findById(id).orElseThrow();
            String name = "Dev " + role.charAt(0) + role.substring(1).toLowerCase();
            user.rename(name, DisplayNames.canonicalise(name));
            users.saveAndFlush(user);
            history.deleteByUserId(id);
        }
    }

    @Test
    void revertWalksBackToThePreviousNameAndFreezesRenaming() {
        rename("Something Rude");
        assertThat(users.findById(fanId).orElseThrow().getDisplayName()).isEqualTo("Something Rude");

        ResponseEntity<String> response = revert();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        User fan = users.findById(fanId).orElseThrow();
        assertThat(fan.getDisplayName()).isEqualTo("Dev Fan");
        // Without the freeze the user renames straight back and the revert meant nothing.
        assertThat(fan.getRenameLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void aSecondRevertWalksFurtherRatherThanOscillating() {
        rename("First Bad Name");
        revert(); // → Dev Fan
        unfreeze();
        rename("Second Bad Name");
        revert(); // → back past "First Bad Name", which a moderator already rejected

        // "First Bad Name" was left by an ADMIN_REVERT, so it is not a target. Bouncing between the two
        // rejected names would make the second revert useless.
        assertThat(users.findById(fanId).orElseThrow().getDisplayName()).isNotEqualTo("First Bad Name");
    }

    @Test
    void aUserWithNoUsableHistoryLandsOnTheGeneratedFloor() {
        // No history at all: there is nothing to walk back to, and an account must still end up named.
        revert();
        assertThat(users.findById(fanId).orElseThrow().getDisplayName())
                .isEqualTo(DisplayNames.generatedFor(fanId));
    }

    @Test
    void revertSkipsANameSomebodyElseNowHolds() {
        rename("Contested Name");
        // Somebody else takes the name the fan left behind. Reverting into it would break the unique index,
        // and the person now holding it did nothing wrong.
        devLogin("podcaster"); // materialises the dev PODCASTER user
        User podcaster = users.findById(userId("PODCASTER")).orElseThrow();
        podcaster.rename("Dev Fan", DisplayNames.canonicalise("Dev Fan"));
        users.save(podcaster);

        revert();

        User fan = users.findById(fanId).orElseThrow();
        assertThat(fan.getDisplayName()).isEqualTo(DisplayNames.generatedFor(fanId));
        assertThat(users.findById(podcaster.getId()).orElseThrow().getDisplayName()).isEqualTo("Dev Fan");
    }

    @Test
    void thereIsNoWayForAnAdminToSetAName() {
        // §8.6.1 in one assertion: the only moderation verb is revert, so PUT /name does not exist.
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users/" + fanId + "/name", HttpMethod.PUT,
                admin.write("{\"displayName\":\"Chosen By Admin\"}"), String.class);
        assertThat(response.getStatusCode())
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.FORBIDDEN);
    }

    @Test
    void aPodcasterCannotRevertAnyone() {
        // PODCASTER is a content role (§8.5); moderation of people is not part of it.
        Session podcaster = devLogin("podcaster");
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users/" + fanId + "/name/revert", HttpMethod.POST,
                podcaster.write("{}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theUserListPagesAndSearchesOnTheCanonicalKey() {
        User fan = users.findById(fanId).orElseThrow();
        fan.rename("Hаrbour Master", DisplayNames.canonicalise("Hаrbour Master")); // Cyrillic а
        users.save(fan);

        // Typed in plain Latin, which is how a moderator would type a reported impersonation.
        ResponseEntity<String> found = rest.exchange(
                "/api/admin/users?q=harbour&page=0&size=10", HttpMethod.GET, admin.read(), String.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).contains("Hаrbour Master").contains("\"totalElements\":1");

        ResponseEntity<String> all = rest.exchange(
                "/api/admin/users?page=0&size=1", HttpMethod.GET, admin.read(), String.class);
        assertThat(all.getBody()).contains("\"size\":1").contains("\"items\":");
    }

    private void rename(String name) {
        ResponseEntity<String> response = rest.exchange("/api/me", HttpMethod.PATCH,
                devLogin("fan").write("{\"displayName\":\"" + name + "\"}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Lifts the cooldown a rename or revert just started, so a test can take the next step. */
    private void unfreeze() {
        User fan = users.findById(fanId).orElseThrow();
        fan.lockRenameUntil(null);
        users.save(fan);
    }

    private ResponseEntity<String> revert() {
        return rest.exchange("/api/admin/users/" + fanId + "/name/revert", HttpMethod.POST,
                admin.write("{}"), String.class);
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

        HttpEntity<String> write(String body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1).split(";")[0]);
            return new HttpEntity<>(body, headers);
        }
    }
}
