// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.feed.Feed;
import dev.mosaicast.core.feed.FeedRepository;
import dev.mosaicast.core.notification.NotificationRepository;
import dev.mosaicast.core.notification.NotificationService;
import dev.mosaicast.core.progress.ListeningProgressRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Duration;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * "May user A reach user B's data" — the horizontal axis, driven through the real filter chain (core#191).
 *
 * <p>The role axis is covered by the admin matrices. This one had no test at all, although it is where the
 * application's own mechanisms live: every {@code /api/me/**} endpoint takes the user id from the session
 * and never from the request, and the lookups that accept an id from the path pair it with the caller's
 * ({@code findByIdAndUserId}). A loosening of either would look exactly like a working endpoint to every
 * test that only ever signs in one person.
 *
 * <p>The two people are the dev profile's fan and podcaster: distinct accounts, and the podcaster outranks
 * the fan, so a leak along the role gradient would show here too. The admin is a third account for the one
 * case — token revocation — where "an administrator can do it" would be the tempting shortcut.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class HorizontalAuthorizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private ListeningProgressRepository progress;

    @Autowired
    private FeedRepository feeds;

    @Autowired
    private EpisodeRefRepository refs;

    @Autowired
    private EpisodeDisplayRepository displays;

    private DevLogin.Cookies fan;
    private DevLogin.Cookies podcaster;
    private UUID fanId;
    private UUID episodeId;

    @BeforeEach
    void setUp() {
        fan = DevLogin.login(rest, "fan");
        podcaster = DevLogin.login(rest, "podcaster");
        fanId = identities.findByProviderAndExternalId("dev", "FAN").orElseThrow().getUserId();
        notifications.deleteAll();
        progress.deleteAll();

        String slug = "horizontal-" + UUID.randomUUID().toString().substring(0, 8);
        Feed feed = Feed.rss("https://example.test/" + slug + ".xml", "Horizontal");
        feed.assignSlugIfAbsent(slug);
        feed = feeds.save(feed);
        EpisodeRef ref = refs.save(EpisodeRef.published(feed.getId(), "guid-" + slug, 1, 1, slug + "-e1"));
        displays.save(new EpisodeDisplay(ref.getId(), new DisplaySnapshot(
                "One", "<p>Notes.</p>", "https://cdn.test/one.mp3", Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofMinutes(30), null, null, "A Host", null)));
        episodeId = ref.getId();
    }

    @Test
    void aNotificationCanOnlyBeMarkedReadByItsRecipient() {
        notificationService.adminWarning(fanId, "Please keep it civil.", null);
        UUID id = notifications.findAll().getFirst().getId();

        // Not "forbidden": to anyone else the notification does not exist, so its existence is not disclosed.
        assertThat(exchange(podcaster, HttpMethod.POST, "/api/me/notifications/" + id + "/read", null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // "Mark all read" is the caller's all, not everybody's.
        assertThat(exchange(podcaster, HttpMethod.POST, "/api/me/notifications/read", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(podcaster, HttpMethod.GET, "/api/me/notifications", null).getBody())
                .doesNotContain(id.toString());

        assertThat(exchange(fan, HttpMethod.GET, "/api/me/notifications/unread-count", null).getBody())
                .contains("\"unread\":1");
        assertThat(exchange(fan, HttpMethod.POST, "/api/me/notifications/" + id + "/read", null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aListeningPositionIsInvisibleAndUntouchableToAnyoneElse() {
        assertThat(exchange(fan, HttpMethod.PUT, "/api/me/progress/" + episodeId, "{\"positionSeconds\":120}")
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Asking for the same episode returns the podcaster's own (absent) position, not the fan's.
        assertThat(exchange(podcaster, HttpMethod.GET, "/api/me/progress?episodeIds=" + episodeId, null)
                .getBody()).isEqualTo("{}");
        // Writing the same episode writes the podcaster's own row...
        exchange(podcaster, HttpMethod.PUT, "/api/me/progress/" + episodeId, "{\"positionSeconds\":5}");
        // ...and erasing everything erases only theirs.
        assertThat(exchange(podcaster, HttpMethod.DELETE, "/api/me/progress", null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(exchange(fan, HttpMethod.GET, "/api/me/progress?episodeIds=" + episodeId, null).getBody())
                .isEqualTo("{\"" + episodeId + "\":120}");
    }

    @Test
    void anAccessTokenCanOnlyBeRevokedByItsOwnerNotEvenByAnAdmin() {
        ResponseEntity<String> created = exchange(podcaster, HttpMethod.POST, "/api/me/tokens", "{\"name\":\"ci\"}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String tokenId = created.getBody().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        assertThat(exchange(fan, HttpMethod.DELETE, "/api/me/tokens/" + tokenId, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(admin, HttpMethod.DELETE, "/api/me/tokens/" + tokenId, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(admin, HttpMethod.GET, "/api/me/tokens", null).getBody()).doesNotContain(tokenId);

        assertThat(exchange(podcaster, HttpMethod.GET, "/api/me/tokens", null).getBody()).contains(tokenId);
        assertThat(exchange(podcaster, HttpMethod.DELETE, "/api/me/tokens/" + tokenId, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private ResponseEntity<String> exchange(DevLogin.Cookies who, HttpMethod method, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        if (json != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(json, headers), String.class);
    }
}
