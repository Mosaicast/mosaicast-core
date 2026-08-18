// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-plugin storage limits, set from the admin surface (ARCHITECTURE §11.1).
 *
 * <p>The case worth pinning is the precedence: the {@code blobs} fixture declares a 8 KiB quota, so an admin
 * grant that was <em>minimised</em> with the manifest instead of replacing it would appear to work and
 * change nothing — which is exactly the failure this feature exists to avoid. The rest is authorization and
 * the operator's bound.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginBlobLimitsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
        // An operator bound, so the clamp has something to clamp against. Unset is the default elsewhere.
        registry.add("mosaicast.plugin-blobs.hard-quota-bytes", () -> 1_048_576L);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PluginBlobService blobs;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String LIMITS = "/api/admin/plugins/blobs/blob-limits";

    /** The fixture's own declaration, restored after each test so ordering cannot matter. */
    @AfterEach
    void clearGrant() {
        blobs.grant("blobs", null, null);
    }

    private ResponseEntity<String> put(String body, String role) {
        DevLogin.Cookies session = DevLogin.login(rest, role);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
        headers.add("X-XSRF-TOKEN", session.token());
        return rest.exchange(LIMITS, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode adminBlobs(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody()).path("blobs");
    }

    @Test
    void anAdminGrantReplacesTheManifestAskRatherThanBeingMinimisedWithIt() {
        // The fixture declares 8192. Under min(manifest, grant) this would still read 8192 — the control
        // would look like it worked and have done nothing, which is the whole reason for the feature.
        ResponseEntity<String> response = put("{\"quotaBytes\":524288,\"maxFileBytes\":65536}", "admin");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode blobsView = adminBlobs(response);
        assertThat(blobsView.path("quotaBytes").asLong()).isEqualTo(524_288);
        assertThat(blobsView.path("maxFileBytes").asLong()).isEqualTo(65_536);
        assertThat(blobsView.path("quotaOverridden").asBoolean()).isTrue();
        // The manifest is still reported, so the form can say what is being overridden.
        assertThat(blobsView.path("declaredQuotaBytes").asLong()).isEqualTo(8192);
    }

    @Test
    void theQuotaActuallyInForceIsTheGrantedOne() {
        // Not just the reported number: the service that refuses uploads has to agree with the admin view.
        put("{\"quotaBytes\":524288,\"maxFileBytes\":65536}", "admin");

        JsonNode quota = JSON.readTree(rest.getForEntity("/api/plugins/blobs/blob/quota", String.class).getBody());

        assertThat(quota.path("quotaBytes").asLong()).isEqualTo(524_288);
        assertThat(quota.path("maxFileBytes").asLong()).isEqualTo(65_536);
    }

    @Test
    void clearingBothLimitsFallsBackToTheManifest() {
        put("{\"quotaBytes\":524288,\"maxFileBytes\":65536}", "admin");

        JsonNode blobsView = adminBlobs(put("{\"quotaBytes\":null,\"maxFileBytes\":null}", "admin"));

        assertThat(blobsView.path("quotaBytes").asLong()).isEqualTo(8192);
        assertThat(blobsView.path("maxFileBytes").asLong()).isEqualTo(4096);
        assertThat(blobsView.path("quotaOverridden").asBoolean()).isFalse();
    }

    @Test
    void eachLimitIsIndependentlyOptional() {
        // Raising the total without touching how large one upload may be is the common case: a media library
        // grows by accumulating ordinary files, not by one enormous one.
        JsonNode blobsView = adminBlobs(put("{\"quotaBytes\":524288,\"maxFileBytes\":null}", "admin"));

        assertThat(blobsView.path("quotaBytes").asLong()).isEqualTo(524_288);
        assertThat(blobsView.path("quotaOverridden").asBoolean()).isTrue();
        assertThat(blobsView.path("maxFileBytes").asLong()).isEqualTo(4096);
        assertThat(blobsView.path("maxFileOverridden").asBoolean()).isFalse();
    }

    @Test
    void anAdminCannotGrantPastTheOperatorsHardCeiling() {
        // Clamped rather than refused, and the clamped value is what is stored — so what the admin is shown
        // afterwards is what is actually in force, never a number that means something else.
        JsonNode blobsView = adminBlobs(put("{\"quotaBytes\":99999999,\"maxFileBytes\":65536}", "admin"));

        assertThat(blobsView.path("quotaBytes").asLong()).isEqualTo(1_048_576);
        assertThat(blobsView.path("hardQuotaBytes").asLong()).isEqualTo(1_048_576);
    }

    @Test
    void refusesANonPositiveLimit() {
        assertThat(put("{\"quotaBytes\":0,\"maxFileBytes\":null}", "admin").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("{\"quotaBytes\":-1,\"maxFileBytes\":null}", "admin").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void isAdminOnlyUnlikeTheConfigForm() {
        // /config is open to PODCASTER so per-field delegation works. Disk is not delegable: how many
        // gigabytes a plugin may occupy is a decision about someone else's server.
        assertThat(put("{\"quotaBytes\":524288,\"maxFileBytes\":null}", "podcaster").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refusesLimitsForAPluginThatStoresNoFiles() {
        DevLogin.Cookies session = DevLogin.login(rest, "admin");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
        headers.add("X-XSRF-TOKEN", session.token());

        ResponseEntity<String> response = rest.exchange("/api/admin/plugins/good/blob-limits", HttpMethod.PUT,
                new HttpEntity<>("{\"quotaBytes\":524288}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void showsNoStoragePanelToAPodcaster() {
        // A podcaster cannot list plugins at all, but /config is open to them so per-field delegation works,
        // and that response carries the same admin view. The storage numbers are redacted out of it
        // server-side, exactly as config values a caller may not edit already are — not hidden by the client.
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
        headers.add("X-XSRF-TOKEN", session.token());

        ResponseEntity<String> response = rest.exchange("/api/admin/plugins/blobs/config", HttpMethod.PUT,
                new HttpEntity<>("{\"caption\":\"hello\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(response.getBody()).path("blobs").isNull()).isTrue();
    }
}
