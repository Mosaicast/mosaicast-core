// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Scope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Plugin channels over the real wire (platformApi 0.20.0, ARCHITECTURE §7.9): a stream opens, a subscription
 * is admitted by the plugin, a publish arrives well within a second, a dropped connection resumes from the
 * ring — and a stream ends when sign-out, a role change, a site closing or the plugin being switched off says
 * it must.
 *
 * <p>Read with the JDK client rather than the test template, which buffers a whole body; heartbeat and resume
 * grace are turned down so a dropped connection is noticed in milliseconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class RealtimeStreamIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
        registry.add("mosaicast.plugin-realtime.heartbeat", () -> "300ms");
        registry.add("mosaicast.plugin-realtime.resume-grace", () -> "5s");
        registry.add("mosaicast.plugin-realtime.streams-per-user", () -> "6");
    }

    private static final Pattern STREAM_ID = Pattern.compile("\"stream\":\"([^\"]+)\"");
    private static final Pattern SUB_ID = Pattern.compile("\"sub\":\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private PluginDataService data;

    private final HttpClient http = HttpClient.newHttpClient();
    private DevLogin.Cookies fan;
    private DevLogin.Cookies podcaster;
    private DevLogin.Cookies admin;

    @BeforeEach
    void signIn() {
        fan = DevLogin.login(rest, "fan");
        podcaster = DevLogin.login(rest, "podcaster");
        admin = DevLogin.login(rest, "admin");
    }

    @AfterEach
    void reopen() {
        // Tests below close the site and switch the plugin off; leave both as the next test expects.
        rest.exchange("/api/admin/plugins/realtime/enabled?value=true", HttpMethod.PUT,
                new HttpEntity<>(writeHeaders(admin)), String.class);
        rest.exchange("/api/admin/site/access", HttpMethod.PUT, new HttpEntity<>(siteAccess("OPEN"),
                writeHeaders(admin)), String.class);
    }

    @Test
    void aPublishReachesTheSubscriberWithinASecond() throws Exception {
        try (Sse stream = open(fan, null)) {
            String streamId = stream.hello(false);
            String sub = subscribe(fan, streamId, "game-1");
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    data.getRaw("realtime", Scope.site(), "rt:joined:game-1").isPresent());

            long sent = System.nanoTime();
            ResponseEntity<String> published = action(podcaster, "publish",
                    "{\"channel\":\"game-1\",\"event\":\"moved\",\"data\":{\"square\":12}}");
            assertThat(published.getBody()).isEqualTo("1");

            Event event = stream.next("msg");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent)).isLessThan(1000);
            assertThat(event.data()).contains("\"sub\":\"" + sub + "\"").contains("\"event\":\"moved\"")
                    .contains("\"data\":{\"square\":12}").contains("\"at\":");
        }
    }

    @Test
    void publishToReachesOnlyItsUser() throws Exception {
        try (Sse fans = open(fan, null); Sse podcasters = open(podcaster, null)) {
            subscribe(fan, fans.hello(false), "game-2");
            subscribe(podcaster, podcasters.hello(false), "game-2");
            action(admin, "publish", "{\"channel\":\"game-2\",\"event\":\"your-turn\",\"data\":1,\"to\":\""
                    + userId("PODCASTER") + "\"}");
            assertThat(podcasters.next("msg").data()).contains("your-turn");
            assertThat(fans.poll(Duration.ofMillis(500), "msg")).isNull();
        }
    }

    @Test
    void thePluginsAuthorizerDecides() throws Exception {
        try (Sse stream = open(fan, null)) {
            String streamId = stream.hello(false);
            ResponseEntity<String> refused = subscribeRaw(fan, streamId, "closed-room");
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void kickEndsTheirSubscriptionAsRefused() throws Exception {
        try (Sse stream = open(fan, null)) {
            String sub = subscribe(fan, stream.hello(false), "game-3");
            action(podcaster, "kick", "{\"channel\":\"game-3\",\"user\":\"" + userId("FAN") + "\"}");
            assertThat(stream.next("refused").data()).contains(sub);
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    data.getRaw("realtime", Scope.site(), "rt:left:game-3").isPresent());
        }
    }

    @Test
    void aDroppedConnectionResumesFromTheRing() throws Exception {
        String lastId;
        String streamId;
        try (Sse stream = open(fan, null)) {
            streamId = stream.hello(false);
            subscribe(fan, streamId, "game-4");
            action(podcaster, "publish", "{\"channel\":\"game-4\",\"event\":\"one\",\"data\":1}");
            lastId = stream.next("msg").id();
        }
        // Missed while the connection was gone: the ring keeps it for the resume.
        action(podcaster, "publish", "{\"channel\":\"game-4\",\"event\":\"two\",\"data\":2}");
        try (Sse again = open(fan, lastId)) {
            assertThat(again.hello(true)).isEqualTo(streamId);
            assertThat(again.next("msg").data()).contains("\"event\":\"two\"");
        }
    }

    @Test
    void aResumeTheRingCannotCoverStartsOver() throws Exception {
        try (Sse stream = open(fan, "nosuchstream:5")) {
            stream.hello(false);
        }
    }

    @Test
    void signingOutEndsTheSessionsStreams() throws Exception {
        DevLogin.Cookies session = DevLogin.login(rest, "fan");
        try (Sse stream = open(session, null)) {
            String sub = subscribe(session, stream.hello(false), "game-5");
            rest.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(writeHeaders(session)), String.class);
            assertThat(stream.next("closed").data()).contains(sub).contains("\"reason\":\"access\"");
        }
    }

    @Test
    void aRoleChangeEndsTheUsersStreams() throws Exception {
        try (Sse stream = open(fan, null)) {
            String sub = subscribe(fan, stream.hello(false), "game-6");
            ResponseEntity<String> changed = rest.exchange("/api/admin/users/" + userId("FAN") + "/role",
                    HttpMethod.PUT, new HttpEntity<>("{\"role\":\"podcaster\"}", writeHeaders(admin)), String.class);
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stream.next("closed").data()).contains(sub).contains("\"reason\":\"access\"");
        }
        // The next test's dev-login puts the role back.
    }

    @Test
    void aClosingSiteEndsStreamsBelowThePassFloor() throws Exception {
        try (Sse fans = open(fan, null); Sse admins = open(admin, null)) {
            String sub = subscribe(fan, fans.hello(false), "game-7");
            admins.hello(false);
            ResponseEntity<String> closed = rest.exchange("/api/admin/site/access", HttpMethod.PUT,
                    new HttpEntity<>(siteAccess("MAINTENANCE"), writeHeaders(admin)), String.class);
            assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(fans.next("closed").data()).contains(sub);
            // Staff keep the site, and their streams.
            assertThat(admins.poll(Duration.ofMillis(500), "closed")).isNull();
        }
    }

    @Test
    void switchingThePluginOffEndsItsSubscriptions() throws Exception {
        try (Sse stream = open(fan, null)) {
            String sub = subscribe(fan, stream.hello(false), "game-8");
            rest.exchange("/api/admin/plugins/realtime/enabled?value=false", HttpMethod.PUT,
                    new HttpEntity<>(writeHeaders(admin)), String.class);
            assertThat(stream.next("closed").data()).contains(sub).contains("\"reason\":\"plugin\"");
        }
    }

    @Test
    void aTokenCannotOpenAStream() throws Exception {
        ResponseEntity<String> created = rest.exchange("/api/me/tokens", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"stream test\"}", writeHeaders(podcaster)), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Matcher secret = Pattern.compile("\"secret\":\"([^\"]+)\"").matcher(created.getBody());
        assertThat(secret.find()).isTrue();
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/api/realtime/stream"))
                .header("Authorization", "Bearer " + secret.group(1)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void anonymousCannotOpenAStream() throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/api/realtime/stream")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private Sse open(DevLogin.Cookies who, String lastEventId) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/realtime/stream"))
                .header("Cookie", who.session() + "; " + who.xsrf())
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<Stream<String>> response = http.send(request.build(), HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);
        return new Sse(response.body());
    }

    private String subscribe(DevLogin.Cookies who, String streamId, String name) {
        ResponseEntity<String> response = subscribeRaw(who, streamId, name);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Matcher m = SUB_ID.matcher(response.getBody());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private ResponseEntity<String> subscribeRaw(DevLogin.Cookies who, String streamId, String name) {
        String body = "{\"stream\":\"" + streamId + "\",\"plugin\":\"realtime\","
                + "\"scope\":{\"type\":\"site\",\"id\":\"main\"},\"name\":\"" + name + "\"}";
        return rest.exchange("/api/realtime/subscriptions", HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(who)), String.class);
    }

    private ResponseEntity<String> action(DevLogin.Cookies who, String name, String payload) {
        String body = "{\"scope\":{\"type\":\"site\",\"id\":\"main\"},\"payload\":" + payload + "}";
        return rest.exchange("/api/plugins/realtime/actions/" + name, HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(who)), String.class);
    }

    private static String siteAccess(String state) {
        return "{\"state\":\"" + state + "\",\"passFloor\":\"ADMIN\",\"staffLoginVisible\":true,"
                + "\"signupsWhileClosed\":false,\"edgeCounting\":false}";
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpHeaders writeHeaders(DevLogin.Cookies who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }

    private record Event(String id, String name, String data) {
    }

    /** An open stream, parsed as it arrives; closing it drops the connection as a closed tab would. */
    private static final class Sse implements AutoCloseable {

        private final Stream<String> lines;
        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final Thread reader;

        Sse(Stream<String> lines) {
            this.lines = lines;
            this.reader = Thread.ofVirtual().start(this::read);
        }

        private void read() {
            String[] current = new String[3];
            try {
                lines.forEach(line -> {
                    if (line.isEmpty()) {
                        if (current[1] != null || current[2] != null) {
                            events.add(new Event(current[0], current[1], current[2]));
                        }
                        current[0] = current[1] = current[2] = null;
                    } else if (line.startsWith("id:")) {
                        current[0] = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        current[1] = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        current[2] = line.substring(5).trim();
                    }
                });
            } catch (RuntimeException ignored) {
                // closed by the test, or by the server
            }
        }

        /** The {@code hello}, asserting whether it resumed; answers the stream id. */
        String hello(boolean resumed) throws InterruptedException {
            Event hello = next("hello");
            assertThat(hello.data()).contains("\"resumed\":" + resumed);
            Matcher m = STREAM_ID.matcher(hello.data());
            assertThat(m.find()).isTrue();
            return m.group(1);
        }

        Event next(String name) throws InterruptedException {
            Event event = poll(Duration.ofSeconds(5), name);
            assertThat(event).as("an '%s' event", name).isNotNull();
            return event;
        }

        /** The next event of this name within the wait, skipping others; null if none came. */
        Event poll(Duration wait, String name) throws InterruptedException {
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                Event event = events.poll(left, TimeUnit.NANOSECONDS);
                if (event == null) {
                    return null;
                }
                if (name.equals(event.name())) {
                    return event;
                }
            }
        }

        @Override
        public void close() {
            lines.close();
            reader.interrupt();
        }
    }
}
