// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.DisplayNames;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The inbox end to end (ARCHITECTURE §17): the revert notice §8.6.1 requires, admin warnings, read state,
 * and the ownership rule that keeps one person out of another's inbox.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class NotificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private NotificationRepository notifications;

    private Session admin;
    private Session fan;
    private UUID fanId;

    @BeforeEach
    void reset() {
        admin = devLogin("admin");
        fan = devLogin("fan");
        fanId = userId("FAN");
        User user = users.findById(fanId).orElseThrow();
        user.rename("Dev Fan", DisplayNames.canonicalise("Dev Fan"));
        user.lockRenameUntil(null);
        users.saveAndFlush(user);
        notifications.deleteAll();
    }

    @Test
    void revertingANameTellsTheUser() {
        // §8.6.1 requires it, and the feature shipped without it: a name that changes with no explanation
        // reads as a bug or a break-in.
        rest.exchange("/api/me", HttpMethod.PATCH, fan.write("{\"displayName\":\"Something Rude\"}"),
                String.class);
        rest.exchange("/api/admin/users/" + fanId + "/name/revert", HttpMethod.POST, admin.write("{}"),
                String.class);

        ResponseEntity<String> inbox = rest.exchange(
                "/api/me/notifications", HttpMethod.GET, fan.read(), String.class);

        assertThat(inbox.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inbox.getBody())
                .contains("\"source\":\"system\"")
                .contains("\"kind\":\"name-reverted\"")
                // Parameters, not a sentence: the shell owns the wording and translates it (§12.7).
                .contains("Something Rude")
                .contains("Dev Fan");
    }

    @Test
    void theRevertNoticeCarriesNoAdminAuthoredText() {
        // The restraint in §8.6.1 would be undone in one message if an admin could write the explanation.
        rest.exchange("/api/me", HttpMethod.PATCH, fan.write("{\"displayName\":\"Another Name\"}"),
                String.class);
        rest.exchange("/api/admin/users/" + fanId + "/name/revert", HttpMethod.POST, admin.write("{}"),
                String.class);

        Notification notice = notifications.findAll().stream()
                .filter(n -> Notification.SOURCE_SYSTEM.equals(n.getSource()))
                .findFirst().orElseThrow();
        assertThat(notice.getKind()).isEqualTo("name-reverted");
        assertThat(notice.getPayload()).containsOnlyKeys("previous", "current");
    }

    @Test
    void anAdminWarningIsFreeTextAndArrives() {
        ResponseEntity<String> sent = rest.exchange("/api/admin/users/" + fanId + "/warn", HttpMethod.POST,
                admin.write("{\"text\":\"Please keep it civil in the bingo chat.\"}"), String.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> inbox = rest.exchange(
                "/api/me/notifications", HttpMethod.GET, fan.read(), String.class);
        assertThat(inbox.getBody())
                .contains("\"source\":\"admin\"")
                .contains("keep it civil");
    }

    @Test
    void aPodcasterCannotWarnAnyone() {
        Session podcaster = devLogin("podcaster");
        ResponseEntity<String> response = rest.exchange("/api/admin/users/" + fanId + "/warn",
                HttpMethod.POST, podcaster.write("{\"text\":\"stop that\"}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unreadCountFallsWhenTheInboxIsRead() {
        rest.exchange("/api/admin/users/" + fanId + "/warn", HttpMethod.POST,
                admin.write("{\"text\":\"first\"}"), String.class);
        rest.exchange("/api/admin/users/" + fanId + "/warn", HttpMethod.POST,
                admin.write("{\"text\":\"second\"}"), String.class);

        assertThat(unread()).isEqualTo(2);
        rest.exchange("/api/me/notifications/read", HttpMethod.POST, fan.write("{}"), String.class);
        assertThat(unread()).isZero();
    }

    @Test
    void aUserCannotMarkSomebodyElsesNotificationRead() {
        rest.exchange("/api/admin/users/" + fanId + "/warn", HttpMethod.POST,
                admin.write("{\"text\":\"for the fan only\"}"), String.class);
        UUID id = notifications.findAll().getFirst().getId();

        // A 404 rather than a 403: the same answer as an id that never existed, so the endpoint cannot be
        // used to confirm somebody else holds a given notification.
        ResponseEntity<String> response = rest.exchange("/api/me/notifications/" + id + "/read",
                HttpMethod.POST, admin.write("{}"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notifications.findById(id).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void anInboxIsOnlyEverTheCallersOwn() {
        rest.exchange("/api/admin/users/" + fanId + "/warn", HttpMethod.POST,
                admin.write("{\"text\":\"for the fan only\"}"), String.class);

        ResponseEntity<String> adminInbox = rest.exchange(
                "/api/me/notifications", HttpMethod.GET, admin.read(), String.class);
        assertThat(adminInbox.getBody()).doesNotContain("for the fan only");
    }

    @Test
    void anAnonymousVisitorHasNoInbox() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/me/notifications", String.class);
        assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    private long unread() {
        ResponseEntity<String> response = rest.exchange(
                "/api/me/notifications/unread-count", HttpMethod.GET, fan.read(), String.class);
        String body = response.getBody();
        return Long.parseLong(body.replaceAll("\\D+", ""));
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
