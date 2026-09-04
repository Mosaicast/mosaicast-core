// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
 * Renaming through the real security + CSRF chain (ARCHITECTURE §8.6): the happy path, each of the four
 * refusals with the problem type the UI translates on, and the history a revert will later walk back
 * through (§8.6.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class DisplayNameIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserNameHistoryRepository history;

    @Autowired
    private LinkedIdentityRepository identities;

    /**
     * Puts the dev FAN user back to a known name with no cooldown running.
     *
     * <p>The Spring context and its database are shared across the class, so a rename in one test leaves a
     * lock that refuses the next one — and the failure appears in whichever test happens to run second,
     * which is not the test that caused it.
     */
    @BeforeEach
    void resetTheFanUser() {
        devLogin("fan");
        User fan = users.findById(userId("FAN")).orElseThrow();
        fan.rename("Dev Fan", DisplayNames.canonicalise("Dev Fan"));
        fan.lockRenameUntil(null);
        users.save(fan);
        history.deleteByUserId(fan.getId());
    }

    @Test
    void aUserRenamesThemselvesAndTheOldNameIsRecorded() {
        Session fan = devLogin("fan");
        UUID id = userId("FAN");
        String before = users.findById(id).orElseThrow().getDisplayName();

        ResponseEntity<String> response = patch(fan, "{\"displayName\":\"  Captain   Maritime \"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        User after = users.findById(id).orElseThrow();
        // Whitespace collapsed, the typed spelling kept, and the key folded underneath it.
        assertThat(after.getDisplayName()).isEqualTo("Captain Maritime");
        assertThat(after.getDisplayKey()).isEqualTo("captain maritime");
        assertThat(history.findByUserIdOrderBySetAtDescIdDesc(id, PageRequest.of(0, 5)))
                .extracting(UserNameHistory::getName)
                .containsExactly(before);
    }

    @Test
    void aRenameStartsACooldownAndTheSecondAttemptIsRefused() {
        Session fan = devLogin("fan");
        UUID id = userId("FAN");

        assertThat(patch(fan, "{\"displayName\":\"First Choice\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(users.findById(id).orElseThrow().getRenameLockedUntil()).isAfter(Instant.now());

        ResponseEntity<String> second = patch(fan, "{\"displayName\":\"Second Thoughts\"}");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("display-name-locked");
        assertThat(users.findById(id).orElseThrow().getDisplayName()).isEqualTo("First Choice");
    }

    @Test
    void resubmittingTheSameNameIsNotARename() {
        Session fan = devLogin("fan");
        UUID id = userId("FAN");
        String current = users.findById(id).orElseThrow().getDisplayName();

        // A no-op edit must not spend the cooldown, or the form reads as broken.
        assertThat(patch(fan, "{\"displayName\":\"" + current + "\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(users.findById(id).orElseThrow().getRenameLockedUntil()).isNull();
    }

    @Test
    void aNameSomebodyElseHoldsIsRefusedEvenSpeltDifferently() {
        Session admin = devLogin("admin");
        Session fan = devLogin("fan");
        assertThat(patch(admin, "{\"displayName\":\"Harbour Master\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Different case, a Cyrillic а, and a doubled space — the same name to §8.6.
        ResponseEntity<String> taken = patch(fan, "{\"displayName\":\"harbour  Mаster\"}");
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody()).contains("display-name-taken");
    }

    @Test
    void reservedNamesAreRefusedWithoutSayingWhichListTheyAreOn() {
        Session fan = devLogin("fan");
        ResponseEntity<String> response = patch(fan, "{\"displayName\":\"Admin\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("display-name-refused");
    }

    @Test
    void aNameThatIsTooShortOrOnlyInvisibleIsABadRequest() {
        Session fan = devLogin("fan");
        assertThat(patch(fan, "{\"displayName\":\"ab\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> invisible = patch(fan, "{\"displayName\":\"\\u200b\\u200b\\u200b\\u200b\"}");
        assertThat(invisible.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invisible.getBody()).contains("display-name-invalid");
    }

    @Test
    void anAnonymousCallerCannotRename() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"displayName\":\"Nobody At All\"}", headers), String.class);
        assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void everyDevUserGotAUniqueKeyOnSignUp() {
        devLogin("fan");
        devLogin("podcaster");
        devLogin("admin");
        List<String> keys = users.findAll().stream().map(User::getDisplayKey).toList();
        assertThat(keys).doesNotContainNull().doesNotHaveDuplicates();
    }

    private ResponseEntity<String> patch(Session session, String body) {
        return rest.exchange("/api/me", HttpMethod.PATCH, session.write(body), String.class);
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }

    private Session devLogin(String role) {
        DevLogin.Cookies cookies = DevLogin.login(rest, role);
        return new Session(cookies.session(), cookies.xsrf());
    }

    private record Session(String sessionCookie, String xsrfCookie) {

        HttpEntity<String> write(String body) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + xsrfCookie);
            headers.add("X-XSRF-TOKEN", xsrfCookie.substring(xsrfCookie.indexOf('=') + 1).split(";")[0]);
            return new HttpEntity<>(body, headers);
        }
    }
}
