// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.mosaicast.core.auth.LinkedIdentityRepository;
import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Scope;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Plugin actions end to end (platformApi 0.20.0, ARCHITECTURE §7.9): every row of the check table, the
 * plugin's own answers, the caller-bound store, and {@code data.backendPrivate} over the doc surface.
 *
 * <p>The {@code realtime} fixture carries the ordinary cases; {@code realtimeslow} the busy and timeout ones,
 * so a held slot cannot starve the rest; {@code realtimetight} asks for three calls a minute, for the 429.
 * Concurrency and deadline are turned down here so those cases take milliseconds, not seconds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginActionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
        registry.add("mosaicast.plugin-realtime.action-concurrency", () -> "1");
        registry.add("mosaicast.plugin-realtime.action-queue-wait", () -> "200ms");
        registry.add("mosaicast.plugin-realtime.action-deadline", () -> "500ms");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LinkedIdentityRepository identities;

    @Autowired
    private PluginDataService data;

    private DevLogin.Cookies fan;
    private DevLogin.Cookies podcaster;
    private DevLogin.Cookies admin;

    @BeforeEach
    void signIn() {
        fan = DevLogin.login(rest, "fan");
        podcaster = DevLogin.login(rest, "podcaster");
        admin = DevLogin.login(rest, "admin");
    }

    // --- the check table, in order -----------------------------------------------------------------------

    @Test
    void aPluginWithoutActionsIs404() {
        ResponseEntity<String> response = call(fan, "keyfloors", "echo", site("1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("problems/not-found");
    }

    @Test
    void anUndeclaredNameIsUnknownAction() {
        ResponseEntity<String> response = call(fan, "realtime", "nope", site("1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("problems/unknown-action");
    }

    @Test
    void anAnonymousCallerIs401() {
        String token = DevLogin.token(rest);
        HttpHeaders headers = json();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        ResponseEntity<String> response = rest.exchange("/api/plugins/realtime/actions/echo", HttpMethod.POST,
                new HttpEntity<>(site("1"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aNamesOwnFloorIsEnforced() {
        assertThat(call(fan, "realtime", "boss", site("1")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> allowed = call(podcaster, "realtime", "boss", site("1"));
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).isEqualTo("\"ok\"");
    }

    @Test
    void aBodyOverTheCapIs413() {
        String big = "{\"scope\":{\"type\":\"site\",\"id\":\"main\"},\"payload\":\"" + "x".repeat(17_000) + "\"}";
        ResponseEntity<String> response = call(fan, "realtime", "echo", big);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).contains("problems/payload-too-large");
    }

    @Test
    void aScopeThatNamesNothingIs404() {
        String body = "{\"scope\":{\"type\":\"episode\",\"id\":\"no-such-episode\"},\"payload\":1}";
        ResponseEntity<String> response = call(fan, "realtime", "echo", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("problems/not-found");
        String user = "{\"scope\":{\"type\":\"user\",\"id\":\"me\"},\"payload\":1}";
        assertThat(call(fan, "realtime", "echo", user).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theBudgetIsAnnouncedWithWhenToRetry() {
        // realtimetight asks for three calls a minute per user; the fourth is refused with a retry hint.
        for (int i = 0; i < 3; i++) {
            assertThat(call(admin, "realtimetight", "nothing", site("1")).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
        ResponseEntity<String> refused = call(admin, "realtimetight", "nothing", site("1"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getBody()).contains("problems/too-many-requests").contains("\"retryAfterMs\"");
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }

    @Test
    void aDeclaredButUnwiredNameIsUnavailable() {
        ResponseEntity<String> response = call(fan, "realtime", "unwired", site("1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("problems/action-unavailable");
    }

    // --- what the handler answers ------------------------------------------------------------------------

    @Test
    void theHandlerSeesAHostResolvedCallerAndScope() {
        String body = "{\"scope\":{\"type\":\"site\",\"id\":\"main\"},\"payload\":{\"move\":7},"
                + "\"caller\":{\"userId\":\"" + UUID.randomUUID() + "\",\"role\":\"ADMIN\"}}";
        ResponseEntity<String> response = call(fan, "realtime", "echo", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Whatever the payload claims, the caller is the session's.
        assertThat(response.getBody())
                .contains("\"user\":\"" + userId("FAN") + "\"")
                .contains("\"role\":\"FAN\"")
                .contains("\"scope\":\"site/main\"")
                .contains("\"payload\":{\"move\":7}")
                .contains("\"receivedAt\"");
    }

    @Test
    void nullIsNoContent() {
        assertThat(call(fan, "realtime", "nothing", site("1")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aRejectionCarriesThePluginsCode() {
        ResponseEntity<String> response = call(fan, "realtime", "reject", site("1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("problems/action-rejected").contains("\"code\":\"not-your-turn\"");
    }

    @Test
    void aThrowingHandlerIsA500ThatSaysNothingOfThePlugin() {
        ResponseEntity<String> response = call(fan, "realtime", "boom", site("1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContain("blew up");
    }

    @Test
    void aPayloadTheHandlerCannotReadIs400() {
        assertThat(call(fan, "realtime", "number", site("41")).getBody()).isEqualTo("42");
        ResponseEntity<String> response = call(fan, "realtime", "number", site("\"forty\""));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("problems/bad-request");
    }

    @Test
    void theCallersStoreReachesTheirOwnPartition() {
        ResponseEntity<String> response = call(fan, "realtime", "whoami", site("1"));
        assertThat(response.getBody()).isEqualTo("\"" + userId("FAN") + "\"");
        ResponseEntity<String> mine = rest.exchange("/api/plugins/realtime/data/user/me/seen", HttpMethod.GET,
                new HttpEntity<>(cookies(fan)), String.class);
        assertThat(mine.getBody()).isEqualTo("\"" + userId("FAN") + "\"");
        ResponseEntity<String> theirs = rest.exchange("/api/plugins/realtime/data/user/me/seen", HttpMethod.GET,
                new HttpEntity<>(cookies(podcaster)), String.class);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aTimerRunsBelowTheScheduleFloor() {
        data.delete("realtime", Scope.site(), "rt:timer-fired");
        assertThat(call(fan, "realtime", "timer", site("50")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> data.getRaw("realtime", Scope.site(), "rt:timer-fired").isPresent());
    }

    // --- budget and deadline -----------------------------------------------------------------------------

    @Test
    void aSlowHandlerTimesOut() {
        ResponseEntity<String> response = call(fan, "realtimeslow", "slow", site("3000"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody()).contains("problems/action-timeout");
    }

    @Test
    void aFullPluginIsBusyWithARetryHint() throws Exception {
        CompletableFuture<ResponseEntity<String>> holder =
                CompletableFuture.supplyAsync(() -> call(podcaster, "realtimeslow", "slow", site("3000")));
        Thread.sleep(150); // let the holder take the plugin's only slot
        ResponseEntity<String> refused = call(fan, "realtimeslow", "nothing", site("1"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody()).contains("problems/action-busy").contains("\"retryAfterMs\"");
        holder.get();
    }

    // --- backendPrivate -----------------------------------------------------------------------------------

    @Test
    void aBackendPrivateKeyIsRefusedToEveryClientAdminsIncluded() {
        for (DevLogin.Cookies who : new DevLogin.Cookies[] {fan, admin}) {
            ResponseEntity<String> read = rest.exchange("/api/plugins/realtime/data/site/main/rt:secret",
                    HttpMethod.GET, new HttpEntity<>(cookies(who)), String.class);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(read.getBody()).contains("problems/backend-private-key");
            ResponseEntity<String> write = rest.exchange("/api/plugins/realtime/data/site/main/rt:secret",
                    HttpMethod.PUT, new HttpEntity<>("\"forged\"", writeHeaders(who)), String.class);
            assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(write.getBody()).contains("problems/backend-private-key");
        }
        ResponseEntity<String> anonymous = rest.getForEntity("/api/plugins/realtime/data/site/main/rt:secret",
                String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The backend still reads its own key.
        assertThat(data.getRaw("realtime", Scope.site(), "rt:secret")).isPresent();
    }

    @Test
    void aBackendPrivateKeyIsAbsentFromListingsAndBatches() {
        ResponseEntity<String> list = rest.exchange("/api/plugins/realtime/data/site/main", HttpMethod.GET,
                new HttpEntity<>(cookies(admin)), String.class);
        assertThat(list.getBody()).contains("\"public\"").doesNotContain("rt:secret");
        ResponseEntity<String> batch = rest.exchange(
                "/api/plugins/realtime/data/site?ids=main&keys=public,rt:secret", HttpMethod.GET,
                new HttpEntity<>(cookies(admin)), String.class);
        assertThat(batch.getBody()).contains("\"public\"").doesNotContain("rt:secret");
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private ResponseEntity<String> call(DevLogin.Cookies who, String plugin, String name, String body) {
        return rest.exchange("/api/plugins/" + plugin + "/actions/" + name, HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(who)), String.class);
    }

    /** A site-scoped call body with the given JSON payload. */
    private static String site(String payloadJson) {
        return "{\"scope\":{\"type\":\"site\",\"id\":\"main\"},\"payload\":" + payloadJson + "}";
    }

    private static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders cookies(DevLogin.Cookies who) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        return headers;
    }

    private static HttpHeaders writeHeaders(DevLogin.Cookies who) {
        HttpHeaders headers = json();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private UUID userId(String devRole) {
        return identities.findByProviderAndExternalId("dev", devRole).orElseThrow().getUserId();
    }
}
