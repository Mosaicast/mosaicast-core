// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Scope;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code data.keyFloors} over HTTP (ARCHITECTURE §7.6, core#259, platformApi 0.19.0): public numbers beside
 * podcaster-only bookkeeping, and an admin-only setting beside podcaster-writable ones, in one plugin.
 *
 * <p>The {@code keyfloors} fixture is anonymous-readable and podcaster-writable, and raises:
 * {@code kf:import:*} / {@code kf:staged:*} to podcaster reads, {@code kf:import:secret} further to admin
 * reads, and {@code kf:bundles} / {@code kf:stats} to admin writes — {@code kf:stats} also being
 * {@code backendOwned}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class KeyFloorsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String SITE = "/api/plugins/keyfloors/data/site/main";

    private static final String KEY_FLOOR = "https://mosaicast.dev/problems/key-floor";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PluginDataService data;

    @BeforeEach
    void seed() {
        // Through the service, as a backend would: a client could not write half of these.
        for (String key : List.of("kf:public", "kf:import:1", "kf:import:2", "kf:import:secret", "kf:importer",
                "kf:staged:a", "kf:stats")) {
            data.put("keyfloors", Scope.site(), key, key);
        }
    }

    @Test
    void aKeyAboveTheReadersFloorIsRefusedWithItsOwnProblemType() {
        assertThat(call(null, HttpMethod.GET, "kf:public", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> refused = call(null, HttpMethod.GET, "kf:import:1", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(refused)).isEqualTo(KEY_FLOOR);
        // Selector `kf:import:*` matches by prefix, so its neighbour `kf:importer` stays public.
        assertThat(call(null, HttpMethod.GET, "kf:importer", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders podcaster = as("podcaster");
        assertThat(call(podcaster, HttpMethod.GET, "kf:import:1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The strictest matching entry wins: `kf:import:secret` is in `kf:import:*` and raised again to admin.
        assertThat(call(podcaster, HttpMethod.GET, "kf:import:secret", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(as("admin"), HttpMethod.GET, "kf:import:secret", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aListingLeavesHiddenKeysOutBeforePagingSoItsTotalsAreHonest() {
        JsonNode anonymous = list(null, "size=2");
        // Visible to anonymous: kf:importer, kf:public, kf:stats — plus whatever the fixture backend wrote
        // under other prefixes, which the `kf` prefix filter keeps out of this count.
        assertThat(anonymous.get("totalElements").asLong()).isEqualTo(3);
        assertThat(anonymous.get("totalPages").asInt()).isEqualTo(2);
        assertThat(keys(anonymous)).containsExactly("kf:importer", "kf:public");
        assertThat(keys(list(null, "size=2&page=1"))).containsExactly("kf:stats");

        JsonNode podcaster = list(as("podcaster"), "size=50");
        assertThat(keys(podcaster)).containsExactly(
                "kf:import:1", "kf:import:2", "kf:importer", "kf:public", "kf:staged:a", "kf:stats");
        assertThat(keys(list(as("admin"), "size=50"))).contains("kf:import:secret").hasSize(7);
    }

    @Test
    void aBatchReadLeavesAHiddenKeyAbsentLikeAMiss() {
        ResponseEntity<String> batch = rest.exchange(
                "/api/plugins/keyfloors/data/site?ids=main&keys=kf:public,kf:import:1,kf:staged:a",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode main = JSON.readTree(batch.getBody()).get("main");
        assertThat(main.has("kf:public")).isTrue();
        assertThat(main.has("kf:import:1")).isFalse();
        assertThat(main.has("kf:staged:a")).isFalse();
    }

    @Test
    void aWriteIsCheckedPluginFloorThenBackendOwnedThenKeyFloor() {
        // A fan is below the plugin's write floor: the plugin-floor refusal, which says nothing about keys.
        ResponseEntity<String> fan = call(as("fan"), HttpMethod.PUT, "kf:bundles", "1");
        assertThat(fan.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(fan)).isEqualTo("https://mosaicast.dev/problems/forbidden");

        ResponseEntity<String> podcaster = call(as("podcaster"), HttpMethod.PUT, "kf:bundles", "1");
        assertThat(podcaster.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(podcaster)).isEqualTo(KEY_FLOOR);
        assertThat(type(call(as("podcaster"), HttpMethod.DELETE, "kf:bundles", null))).isEqualTo(KEY_FLOOR);

        HttpHeaders admin = as("admin");
        assertThat(call(admin, HttpMethod.PUT, "kf:bundles", "1").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Backend-owned stays backend-owned, whatever its write floor — and the refusal names that rule.
        ResponseEntity<String> owned = call(admin, HttpMethod.PUT, "kf:stats", "1");
        assertThat(owned.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(owned)).isEqualTo("https://mosaicast.dev/problems/backend-owned-key");
    }

    @Test
    void theUserScopeIgnoresKeyFloors() {
        HttpHeaders fan = as("fan");
        ResponseEntity<String> put = rest.exchange("/api/plugins/keyfloors/data/user/me/kf:import:mine",
                HttpMethod.PUT, new HttpEntity<>("\"mine\"", json(fan)), String.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange("/api/plugins/keyfloors/data/user/me/kf:import:mine", HttpMethod.GET,
                new HttpEntity<>(fan), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theAdminPluginPageReportsTheFloorsTheHostEnforces() {
        ResponseEntity<String> plugins = rest.exchange("/api/admin/plugins", HttpMethod.GET,
                new HttpEntity<>(as("admin")), String.class);
        JsonNode keyfloors = null;
        for (JsonNode plugin : JSON.readTree(plugins.getBody())) {
            if ("keyfloors".equals(plugin.path("id").asString())) {
                keyfloors = plugin.get("access");
            }
        }
        assertThat(keyfloors).isNotNull();
        assertThat(keyfloors.get("dataRead").asString()).isEqualTo("anonymous");
        assertThat(keyfloors.get("dataWrite").asString()).isEqualTo("podcaster");
        assertThat(keyfloors.get("schemaRead").isNull()).isTrue();
        assertThat(keyfloors.get("keyFloors")).hasSize(3);
        assertThat(keyfloors.get("keyFloors").get(1).get("writableBy").asString()).isEqualTo("admin");
    }

    // ---- helpers ----

    private ResponseEntity<String> call(HttpHeaders session, HttpMethod method, String key, String body) {
        HttpHeaders headers = session == null ? new HttpHeaders() : json(session);
        return rest.exchange(SITE + "/" + key, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode list(HttpHeaders session, String query) {
        ResponseEntity<String> response = rest.exchange(SITE + "?prefix=kf:&" + query, HttpMethod.GET,
                new HttpEntity<>(session == null ? new HttpHeaders() : session), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private static List<String> keys(JsonNode page) {
        List<String> keys = new ArrayList<>();
        page.get("items").forEach(item -> keys.add(item.get("key").asString()));
        return keys;
    }

    private static String type(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody()).path("type").asString();
    }

    private HttpHeaders as(String role) {
        DevLogin.Cookies who = DevLogin.login(rest, role);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }

    private static HttpHeaders json(HttpHeaders session) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(session);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
