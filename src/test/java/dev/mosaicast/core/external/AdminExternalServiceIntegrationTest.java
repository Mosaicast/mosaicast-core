// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.external.support.StubProviderConfig;
import dev.mosaicast.core.support.DevLogin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The external-services admin surface end to end (ARCHITECTURE §12.7): who may reach it, what it will store,
 * and — the part worth the most — what it refuses to say back about a credential.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
@Import(StubProviderConfig.class)
class AdminExternalServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    /**
     * Every test starts from "nothing configured".
     *
     * <p>These share one context and one database, and JUnit does not promise an order, so a selection made
     * by one method was arriving in the next as if an admin had made it. Reset goes through the API rather
     * than deleting rows, so the service's caches are invalidated the same way a real write does — clearing
     * the tables underneath them would leave the caches holding the old answer.
     */
    @BeforeEach
    void reset() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        Map<String, Object> cleared = new java.util.HashMap<>();
        for (String key : List.of("baseUrl", "apiKey", "requestsPerMinute", "format", "verify")) {
            cleared.put(key, null);
        }
        put(admin, "/api/admin/external/translation/providers/stub/settings", cleared, String.class);
        Map<String, Object> none = new java.util.HashMap<>();
        none.put("providerId", null);
        put(admin, "/api/admin/external/translation/provider", none, String.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void nothingIsSelectedUntilAnAdminSelectsIt() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        List<Map<String, Object>> sections = rest.exchange("/api/admin/external", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), List.class).getBody();
        Map<String, Object> translation = sections.stream()
                .filter(section -> "translation".equals(section.get("kind")))
                .findFirst().orElseThrow();

        // Absent means off — the opposite of plugin activation, because a service nobody configured must
        // make no outbound call at all.
        assertThat(translation.get("selectedProviderId")).isNull();
        assertThat(translation.get("ready")).isEqualTo(false);
        // The stub plus whatever real providers ship; the count is not the point, the stub's presence is.
        assertThat((List<Map<String, Object>>) translation.get("providers"))
                .extracting(provider -> provider.get("id"))
                .contains(StubProviderConfig.ID);
    }

    @Test
    void onlyAdminsMayReachIt() {
        assertThat(rest.getForEntity("/api/admin/external", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        for (String role : List.of("fan", "podcaster")) {
            DevLogin.Cookies other = DevLogin.login(rest, role);
            assertThat(rest.exchange("/api/admin/external", HttpMethod.GET,
                    new HttpEntity<>(headers(other)), String.class).getStatusCode())
                    .as("%s must not read external services", role)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void selectingAProviderAndSettingItUpMakesItReady() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        Map<String, Object> after = put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", StubProviderConfig.ID), Map.class).getBody();
        assertThat(after.get("selectedProviderId")).isEqualTo(StubProviderConfig.ID);
        // baseUrl is required and has no default, so a fresh selection is not yet usable.
        assertThat(after.get("ready")).isEqualTo(false);
        assertThat((List<String>) after.get("missingSettings")).containsExactly("baseUrl");

        Map<String, Object> configured = put(admin,
                "/api/admin/external/translation/providers/stub/settings",
                Map.of("baseUrl", "http://localhost:5000", "requestsPerMinute", 120), Map.class).getBody();

        assertThat(configured.get("ready")).isEqualTo(true);
        assertThat((List<String>) configured.get("missingSettings")).isEmpty();
        assertThat(field(configured, "baseUrl").get("value")).isEqualTo("http://localhost:5000");
        assertThat(field(configured, "requestsPerMinute").get("overridden")).isEqualTo(true);
        // Untouched fields still report their descriptor default rather than a null the form would render.
        assertThat(field(configured, "format").get("defaultValue")).isEqualTo("text");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aStoredCredentialIsNeverReadBack() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", StubProviderConfig.ID), Map.class);

        Map<String, Object> saved = put(admin, "/api/admin/external/translation/providers/stub/settings",
                Map.of("baseUrl", "http://localhost:5000", "apiKey", "sk-live-secret-1234"),
                Map.class).getBody();

        Map<String, Object> apiKey = field(saved, "apiKey");
        // The whole point: `set` says there is one, `value` says nothing. Not a redaction branch — the
        // mapper has no path that reads a credential.
        assertThat(apiKey.get("set")).isEqualTo(true);
        assertThat(apiKey.get("value")).isNull();
        assertThat(saved.toString()).doesNotContain("sk-live-secret-1234");

        // And it does not come back through a plain read either.
        String raw = rest.exchange("/api/admin/external/translation", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), String.class).getBody();
        assertThat(raw).doesNotContain("sk-live-secret-1234");
    }

    @SuppressWarnings("unchecked")
    @Test
    void anOptionalCredentialDoesNotBlockReadiness() {
        // The case that matters for a self-hosted LibreTranslate: keyRequired:false, so no token exists and
        // the provider still has to be usable.
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", StubProviderConfig.ID), Map.class);

        Map<String, Object> ready = put(admin, "/api/admin/external/translation/providers/stub/settings",
                Map.of("baseUrl", "http://localhost:5000"), Map.class).getBody();

        assertThat(ready.get("ready")).isEqualTo(true);
        assertThat(field(ready, "apiKey").get("set")).isEqualTo(false);
        assertThat(field(ready, "adminKey").get("set")).isEqualTo(false);
        // An env-backed field still tells the operator exactly what to export.
        assertThat(field(ready, "adminKey").get("envVar"))
                .isEqualTo("MOSAICAST_EXTERNAL_TRANSLATION_STUB_ADMIN_KEY");
    }

    @Test
    void aRejectedBatchStoresNothing() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", StubProviderConfig.ID), Map.class);

        ResponseEntity<String> refused = put(admin,
                "/api/admin/external/translation/providers/stub/settings",
                Map.of("baseUrl", "http://ok:5000", "requestsPerMinute", 0, "nonsense", "x"), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Every bad row named, not just the first — one round trip, whole form annotated.
        assertThat(refused.getBody()).contains("requestsPerMinute").contains("nonsense");

        // All-or-nothing: the good field in that same batch was not written either.
        @SuppressWarnings("unchecked")
        Map<String, Object> section = rest.exchange("/api/admin/external/translation", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), Map.class).getBody();
        assertThat(field(section, "baseUrl").get("value")).isNull();
    }

    @Test
    void anUnknownProviderOrKindIsRefused() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        assertThat(put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", "nope"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rest.exchange("/api/admin/external/telepathy", HttpMethod.GET,
                new HttpEntity<>(headers(admin)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theTestButtonRefusesUntilTheProviderCanActuallyRun() {
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");

        // Nothing selected → 409, with its own problem type rather than a generic failure.
        ResponseEntity<String> none = rest.exchange("/api/admin/external/translation/test", HttpMethod.POST,
                new HttpEntity<>(headers(admin)), String.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody()).contains("external-no-provider");

        put(admin, "/api/admin/external/translation/provider",
                Map.of("providerId", StubProviderConfig.ID), Map.class);

        // Selected but unconfigured → a different 409, naming the field and never a value.
        ResponseEntity<String> unconfigured = rest.exchange("/api/admin/external/translation/test",
                HttpMethod.POST, new HttpEntity<>(headers(admin)), String.class);
        assertThat(unconfigured.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unconfigured.getBody()).contains("external-provider-misconfigured").contains("baseUrl");

        put(admin, "/api/admin/external/translation/providers/stub/settings",
                Map.of("baseUrl", "http://localhost:5000"), Map.class);

        Map<String, Object> probe = rest.exchange("/api/admin/external/translation/test", HttpMethod.POST,
                new HttpEntity<>(headers(admin)), Map.class).getBody();
        assertThat(probe.get("ok")).isEqualTo(true);
        assertThat((String) probe.get("detail")).contains("localhost:5000");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> section, String key) {
        List<Map<String, Object>> providers = (List<Map<String, Object>>) section.get("providers");
        // By id, not position: real providers (LibreTranslate) share this section, and `getFirst()` silently
        // read the wrong one's fields the moment one shipped.
        Map<String, Object> stub = providers.stream()
                .filter(provider -> StubProviderConfig.ID.equals(provider.get("id")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> fields = (List<Map<String, Object>>) stub.get("fields");
        return fields.stream().filter(f -> key.equals(f.get("key"))).findFirst().orElseThrow();
    }

    private <T> ResponseEntity<T> put(DevLogin.Cookies admin, String path, Object body, Class<T> type) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers(admin)), type);
    }

    private static HttpHeaders headers(DevLogin.Cookies admin) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, admin.session() + "; " + admin.xsrf());
        headers.add("X-XSRF-TOKEN", admin.token());
        return headers;
    }
}
