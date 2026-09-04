// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.notification.NotificationRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code ctx.notify} end to end (ARCHITECTURE §17.1).
 *
 * <p>The assertions that matter are the bounds. This is the only plugin surface that writes into another
 * user's view of the site, so what is tested is mostly what it refuses: a user the plugin holds no data
 * for, an off-site link, another plugin's subtree, and a plugin that never declared the block.
 *
 * <p>The {@code directory} fixture declares {@code notifications}; every other fixture is the undeclared
 * case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginNotifyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private PluginDataRepository pluginData;

    private Session fan;
    private UUID fanId;
    private UUID podcasterId;

    @BeforeEach
    void participants() {
        fan = devLogin("fan");
        devLogin("podcaster");
        fanId = userId("FAN");
        podcasterId = userId("PODCASTER");
        notifications.deleteAll();
        pluginData.deleteAll();
        // What makes a user notifiable is a row in the plugin's USER scope — which is also how they became
        // a participant. There is no separate allow-list to keep in step (§17.1).
        pluginData.save(new PluginData(
                new PluginDataKey("directory", "USER", fanId.toString(), "card"),
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void notifiesAParticipantAndAnswersWhoGotIt() {
        ResponseEntity<String> response = send("""
                {"userIds":["%s"],"text":{"en":"Bingo resolved","de":"Bingo aufgelöst"}}"""
                .formatted(fanId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(fanId.toString());
        assertThat(notifications.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getSource()).isEqualTo("plugin:directory");
            // Every language the plugin had, because it cannot know which the reader will have (§17.1).
            assertThat(n.getPayload()).containsEntry("en", "Bingo resolved")
                    .containsEntry("de", "Bingo aufgelöst");
        });
    }

    @Test
    void aUserThePluginHoldsNoDataForIsDroppedRatherThanRejected() {
        // The eligibility rule. Dropping rather than rejecting matters twice: one stale participant must
        // not cost the others their notification, and "that user exists but is not yours" would answer a
        // question a plugin should not be able to ask.
        ResponseEntity<String> response = send("""
                {"userIds":["%s","%s"],"text":{"en":"hello"}}""".formatted(fanId, podcasterId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(fanId.toString()).doesNotContain(podcasterId.toString());
        assertThat(notifications.findAll()).hasSize(1);
    }

    @Test
    void anOffSiteLinkIsRefused() {
        // A notification is chrome the site speaks through; a plugin that could aim one anywhere could
        // phish the site's own users in the site's own voice.
        for (String link : new String[] {"https://evil.example", "//evil.example", "javascript:alert(1)"}) {
            ResponseEntity<String> response = send("""
                    {"userIds":["%s"],"text":{"en":"hi"},"link":"%s"}""".formatted(fanId, link));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("notification-invalid-link");
        }
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void aLinkIntoAnotherPluginsSubtreeIsRefused() {
        ResponseEntity<String> response = send("""
                {"userIds":["%s"],"text":{"en":"hi"},"link":"/p/wiki/secret"}""".formatted(fanId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBareLinkIsResolvedIntoThePluginsOwnSubtree() {
        ResponseEntity<String> response = send("""
                {"userIds":["%s"],"text":{"en":"hi"},"link":"board/42"}""".formatted(fanId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notifications.findAll().getFirst().getLink()).isEqualTo("/p/directory/board/42");
    }

    @Test
    void aMessageWithoutEnglishIsRefused() {
        // English is the one language a site cannot switch off (§12.7), so it is the only safe fallback.
        ResponseEntity<String> response = send("""
                {"userIds":["%s"],"text":{"de":"nur deutsch"}}""".formatted(fanId));
        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void aPluginWithNoNotificationsBlockHasNoSurfaceAtAll() {
        // 404, indistinguishable from an unknown plugin, so a page cannot probe the manifest set.
        ResponseEntity<String> response = rest.exchange("/api/plugins/good/notify", HttpMethod.POST,
                fan.write("""
                        {"userIds":["%s"],"text":{"en":"hi"}}""".formatted(fanId)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAnonymousCallerCannotSend() {
        // Every other open plugin surface only hands data out; this one writes into other people's inboxes.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/plugins/directory/notify", HttpMethod.POST,
                new HttpEntity<>("{\"userIds\":[\"" + fanId + "\"],\"text\":{\"en\":\"hi\"}}", headers),
                String.class);
        assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(notifications.findAll()).isEmpty();
    }

    @Test
    void thePerRecipientAllowanceComesFromTheManifest() {
        // Spent against the *podcaster*, not the fan. The limiter is in-memory and lives for the whole
        // context, so burning the fan's window here would drop the sends every other test in this class
        // makes — and the failure would surface in whichever test ran next.
        pluginData.save(new PluginData(
                new PluginDataKey("directory", "USER", podcasterId.toString(), "card"),
                tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()));

        // The `directory` fixture asks for 5/day, so the sixth is dropped for that recipient: the manifest
        // number is a real limit rather than documentation (§17.1).
        for (int i = 0; i < 5; i++) {
            assertThat(send("""
                    {"userIds":["%s"],"text":{"en":"message %d"}}""".formatted(podcasterId, i))
                    .getBody()).contains(podcasterId.toString());
        }
        assertThat(send("""
                {"userIds":["%s"],"text":{"en":"one too many"}}""".formatted(podcasterId)).getBody())
                .doesNotContain(podcasterId.toString());
        assertThat(notifications.findAll()).hasSize(5);
    }

    @Test
    void theManifestTellsTheShellWhichPluginsMaySend() {
        ResponseEntity<String> manifest = rest.getForEntity("/api/plugins/manifest", String.class);
        assertThat(manifest.getBody())
                .contains("\"hasNotifications\":true")
                .contains("\"hasNotifications\":false");
    }

    private ResponseEntity<String> send(String body) {
        return rest.exchange("/api/plugins/directory/notify", HttpMethod.POST, fan.write(body), String.class);
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
