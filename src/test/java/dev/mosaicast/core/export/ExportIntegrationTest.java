// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.export;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GDPR data export end to end (ARCHITECTURE §12.8.1, core#263): ask, wait, be told, download one ZIP —
 * and the outcomes that keep it honest: a plugin with no handler is {@code not-supported}, a switched-off one
 * {@code outstanding}, a throwing one {@code failed}; nobody but the owner reaches the archive; and deleting the
 * account takes the archive with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class ExportIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    /** Each test starts outside the one-a-day interval; the interval itself is asserted where it matters. */
    @BeforeEach
    void forgetEarlierExports() {
        jdbc.update("delete from user_data_export");
    }

    @Test
    void aPersonAsksIsToldAndDownloadsOneArchiveWithEveryPluginAccountedFor() throws Exception {
        HttpHeaders fan = as("fan");
        String userId = userIdOf(fan);

        ResponseEntity<String> receipt = call(fan, HttpMethod.POST, "/api/me/export");
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String exportId = JSON.readTree(receipt.getBody()).path("id").asString();
        // One a day: a second request inside the interval is refused, not queued.
        assertThat(call(fan, HttpMethod.POST, "/api/me/export").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(awaitStatus(fan)).isEqualTo("ready");
        ResponseEntity<byte[]> download = rest.exchange("/api/me/export/" + exportId + "/download",
                HttpMethod.GET, new HttpEntity<>(fan), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(download.getHeaders().getCacheControl()).isEqualTo("no-store");

        Map<String, String> zip = entries(download.getBody());
        assertThat(zip).containsKeys("README.txt", "core/account.json", "core/listening.json",
                "core/notifications.json", "outcome.json", "plugins/good/marks/fixture.json");
        assertThat(zip.get("core/account.json")).contains(userId).contains("\"provider\" : \"dev\"");
        assertThat(zip.get("plugins/good/marks/fixture.json")).contains(userId);

        // Every plugin that could hold data has an outcome: nothing is silently missing from the archive.
        Map<String, String> outcomes = outcomes(zip.get("outcome.json"));
        assertThat(outcomes.get("good")).isEqualTo("complete");
        assertThat(outcomes).containsKeys("tagger", "wikifix", "blobs", "keyfloors");

        // Told, with a link to where the download is.
        String inbox = rest.exchange("/api/me/notifications", HttpMethod.GET, new HttpEntity<>(fan), String.class)
                .getBody();
        assertThat(inbox).contains("export-ready").contains("/account#export");

        // Nobody else reaches it — not another person, not an admin, not anonymous.
        HttpHeaders admin = as("admin");
        assertThat(rest.exchange("/api/me/export/" + exportId + "/download", HttpMethod.GET,
                new HttpEntity<>(admin), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/me/export/" + exportId + "/download", String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        // The admin view has the job and the outcomes, and no way to the archive.
        String jobs = rest.exchange("/api/admin/exports", HttpMethod.GET, new HttpEntity<>(admin), String.class)
                .getBody();
        assertThat(jobs).contains(exportId).contains("\"outcome\":\"complete\"").doesNotContain("download");
    }

    @Test
    void aFailingPluginAndASwitchedOffOneAreNamedNotDropped() throws Exception {
        HttpHeaders admin = as("admin");
        HttpHeaders podcaster = as("podcaster");
        setConfig(admin, "{\"failExport\":true}");
        call(admin, HttpMethod.PUT, "/api/admin/plugins/blobs/enabled?value=false");
        try {
            call(podcaster, HttpMethod.POST, "/api/me/export");
            assertThat(awaitStatus(podcaster)).isEqualTo("ready");
            String exportId = JSON.readTree(call(podcaster, HttpMethod.GET, "/api/me/export").getBody())
                    .path("id").asString();
            byte[] archive = rest.exchange("/api/me/export/" + exportId + "/download", HttpMethod.GET,
                    new HttpEntity<>(podcaster), byte[].class).getBody();

            Map<String, String> zip = entries(archive);
            Map<String, String> outcomes = outcomes(zip.get("outcome.json"));
            assertThat(outcomes.get("good")).isEqualTo("failed");
            assertThat(outcomes.get("blobs")).isEqualTo("outstanding");
            assertThat(zip.keySet()).noneMatch(path -> path.startsWith("plugins/good/"));
        } finally {
            setConfig(admin, "{\"failExport\":false}");
            call(admin, HttpMethod.PUT, "/api/admin/plugins/blobs/enabled?value=true");
        }
    }

    @Test
    void deletingTheAccountTakesTheArchiveWithIt() throws Exception {
        HttpHeaders fan = as("fan");
        UUID userId = UUID.fromString(userIdOf(fan));
        call(fan, HttpMethod.POST, "/api/me/export");
        assertThat(awaitStatus(fan)).isEqualTo("ready");
        UUID blobId = jdbc.queryForObject("select blob_id from user_data_export where user_id = ?", UUID.class,
                userId);

        assertThat(call(fan, HttpMethod.DELETE, "/api/me").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject("select count(*) from user_data_export where user_id = ?", Long.class,
                userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from blob where id = ?", Long.class, blobId)).isZero();
    }

    // ---- helpers ----

    private String awaitStatus(HttpHeaders session) throws InterruptedException {
        String status = null;
        for (int i = 0; i < 100; i++) {
            ResponseEntity<String> latest = call(session, HttpMethod.GET, "/api/me/export");
            status = latest.getBody() == null ? null : JSON.readTree(latest.getBody()).path("status").asString();
            if ("ready".equals(status) || "failed".equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }

    private static Map<String, String> entries(byte[] archive) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static Map<String, String> outcomes(String outcomeJson) {
        Map<String, String> byPlugin = new LinkedHashMap<>();
        for (JsonNode plugin : JSON.readTree(outcomeJson).get("plugins")) {
            byPlugin.put(plugin.get("plugin").asString(), plugin.get("outcome").asString());
        }
        return byPlugin;
    }

    private void setConfig(HttpHeaders admin, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(admin);
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange("/api/admin/plugins/good/config", HttpMethod.PUT, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> call(HttpHeaders session, HttpMethod method, String path) {
        return rest.exchange(path, method, new HttpEntity<>(session), String.class);
    }

    private String userIdOf(HttpHeaders session) {
        return JSON.readTree(call(session, HttpMethod.GET, "/api/me").getBody()).path("id").asString();
    }

    private HttpHeaders as(String role) {
        DevLogin.Cookies who = DevLogin.login(rest, role);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, who.session() + "; " + who.xsrf());
        headers.add("X-XSRF-TOKEN", who.token());
        return headers;
    }
}
