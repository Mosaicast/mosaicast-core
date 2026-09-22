// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The plugin file surface end to end (ARCHITECTURE §11, issue #81), against the {@code blobs} fixture —
 * which declares deliberately tiny limits (4 KB per file, 8 KB total, PNG and GIF only) so the ceilings are
 * reachable in a test rather than theoretical.
 *
 * <p>As with the schema surface, <strong>the refusals are what matter</strong>: a type the plugin may not
 * store, a file bigger than it may keep, a collection over quota, another plugin's ref, and a plugin that
 * never asked for file storage at all. Each has to answer with its own status and never a 500.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginBlobControllerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private TestRestTemplate rest;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "/api/plugins/blobs/blob";

    /** A real 1×1 PNG: the content check reads the leading bytes, so a placeholder string would be refused. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** A real GIF header — the fixture allows GIF too, which is what makes the allow-list check meaningful. */
    private static final byte[] GIF = "GIF89a".getBytes(StandardCharsets.US_ASCII);

    // ---- helpers ----

    private ResponseEntity<String> upload(byte[] content, String filename, String declaredType,
                                          DevLogin.Cookies session) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource part = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(declaredType));
        body.add("file", new HttpEntity<>(part, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
            headers.add("X-XSRF-TOKEN", session.token());
        }
        return rest.exchange(BASE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String refOf(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody()).path("ref").asString();
    }

    private void deleteAll() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        JsonNode listing = JSON.readTree(get(BASE + "?size=200", null).getBody());
        for (JsonNode item : listing.path("items")) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
            headers.add("X-XSRF-TOKEN", session.token());
            rest.exchange(BASE + "/" + item.path("ref").asString(), HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class);
        }
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers == null ? new HttpHeaders() : headers),
                String.class);
    }

    // ---- the happy path ----

    @Test
    void storesServesAndDeletesAFile() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            ResponseEntity<String> created = upload(PNG, "diagram.png", "image/png", session);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            JsonNode stored = JSON.readTree(created.getBody());
            String ref = stored.path("ref").asString();
            assertThat(stored.path("mime").asString()).isEqualTo("image/png");
            assertThat(stored.path("filename").asString()).isEqualTo("diagram.png");
            assertThat(stored.path("size").asLong()).isEqualTo(PNG.length);
            // The URL is derived from the ref, and it is the ref a plugin is told to keep.
            assertThat(stored.path("url").asString()).isEqualTo(BASE + "/" + ref);

            // Reads are anonymous here because the fixture declares readableBy: anonymous.
            ResponseEntity<byte[]> served = rest.getForEntity(BASE + "/" + ref, byte[].class);
            assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(served.getBody()).isEqualTo(PNG);
            assertThat(served.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
            assertThat(served.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("inline; filename=\"diagram.png\"");
            assertThat(served.getHeaders().getETag()).isNotBlank();

            HttpHeaders auth = new HttpHeaders();
            auth.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
            auth.add("X-XSRF-TOKEN", session.token());
            ResponseEntity<String> deleted =
                    rest.exchange(BASE + "/" + ref, HttpMethod.DELETE, new HttpEntity<>(auth), String.class);
            assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            // Idempotent: a second delete is still 204, so cleanup needs no existence check.
            assertThat(rest.exchange(BASE + "/" + ref, HttpMethod.DELETE, new HttpEntity<>(auth), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(rest.getForEntity(BASE + "/" + ref, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            deleteAll();
        }
    }

    @Test
    void servesAByteRange() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            String ref = refOf(upload(PNG, "diagram.png", "image/png", session));

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RANGE, "bytes=0-9");
            ResponseEntity<byte[]> ranged =
                    rest.exchange(BASE + "/" + ref, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            assertThat(ranged.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(ranged.getBody()).hasSize(10);
            assertThat(ranged.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                    .isEqualTo("bytes 0-9/" + PNG.length);
        } finally {
            deleteAll();
        }
    }

    @Test
    void listsNewestFirstWithAPagedEnvelope() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            upload(PNG, "first.png", "image/png", session);
            upload(PNG, "second.png", "image/png", session);

            JsonNode listing = JSON.readTree(get(BASE + "?page=0&size=1", null).getBody());

            assertThat(listing.path("totalElements").asLong()).isEqualTo(2);
            assertThat(listing.path("totalPages").asInt()).isEqualTo(2);
            assertThat(listing.path("items")).hasSize(1);
        } finally {
            deleteAll();
        }
    }

    @Test
    void reportsTheEffectiveQuotaRatherThanWhatTheManifestAsked() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            upload(PNG, "a.png", "image/png", session);

            JsonNode quota = JSON.readTree(get(BASE + "/quota", null).getBody());

            // The fixture's own numbers, both well under the install's ceilings, so they survive the min().
            assertThat(quota.path("maxFileBytes").asLong()).isEqualTo(4096);
            assertThat(quota.path("quotaBytes").asLong()).isEqualTo(8192);
            assertThat(quota.path("usedBytes").asLong()).isEqualTo(PNG.length);
        } finally {
            deleteAll();
        }
    }

    // ---- the refusals ----

    @Test
    void refusesATypeTheManifestDoesNotDeclare() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");

        ResponseEntity<String> refused = upload("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "notes.pdf",
                "application/pdf", session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(JSON.readTree(refused.getBody()).path("type").asString())
                .isEqualTo("https://mosaicast.dev/problems/blob-type-not-allowed");
    }

    @Test
    void refusesAFileWhoseContentContradictsItsDeclaredType() {
        // The case a header check alone cannot catch, and the reason the bytes are read: an SVG announcing
        // itself as a PNG is exactly how a script gets stored and served back as an image (§12.2).
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");

        ResponseEntity<String> refused = upload(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8),
                "actually-a-script.png", "image/png", session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // The response must not report what it turned out to be — that would be a sniffer oracle.
        assertThat(refused.getBody()).doesNotContain("svg");
    }

    @Test
    void refusesAFileOverThePerFileCeiling() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        byte[] big = new byte[5000];
        System.arraycopy(PNG, 0, big, 0, PNG.length);

        ResponseEntity<String> refused = upload(big, "big.png", "image/png", session);

        // Compared by code rather than by enum constant: 413 is spelled PAYLOAD_TOO_LARGE in one Spring
        // version and CONTENT_TOO_LARGE in the next, and the wire is what a client sees either way.
        assertThat(refused.getStatusCode().value()).isEqualTo(413);
        assertThat(JSON.readTree(refused.getBody()).path("type").asString())
                .isEqualTo("https://mosaicast.dev/problems/blob-quota-exceeded");
    }

    @Test
    void refusesAWriteThatWouldExceedTheQuota() {
        // Each file fits; the collection does not. This is the failure a plugin actually meets, and it has
        // to be distinguishable from "this file is too big" — the fixes are different.
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        byte[] chunk = new byte[3000];
        System.arraycopy(PNG, 0, chunk, 0, PNG.length);
        try {
            assertThat(upload(chunk, "a.png", "image/png", session).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(upload(chunk, "b.png", "image/png", session).getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<String> refused = upload(chunk, "c.png", "image/png", session);

            assertThat(refused.getStatusCode().value()).isEqualTo(413);
            assertThat(JSON.readTree(refused.getBody()).path("detail").asString()).contains("quota");
        } finally {
            deleteAll();
        }
    }

    @Test
    void refusesAWriteBelowTheManifestsWriteFloor() {
        // The fixture writes at `podcaster`; a fan is above the read floor and below the write one.
        DevLogin.Cookies fan = DevLogin.login(rest, "fan");

        assertThat(upload(PNG, "diagram.png", "image/png", fan).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refusesAnAnonymousWrite() {
        // With a valid CSRF token and no session, so the refusal that lands is the authentication one rather
        // than the CSRF filter's — otherwise this would pass even if the write floor were wide open.
        String csrf = DevLogin.token(rest);
        DevLogin.Cookies anonymous = new DevLogin.Cookies("MOSAICAST_SESSION=none", "XSRF-TOKEN=" + csrf);

        assertThat(upload(PNG, "diagram.png", "image/png", anonymous).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void answers404ForAPluginThatStoresNoFiles() {
        // `good` is loaded, switched on, and declares no `blobs` block: no file surface at all, and
        // indistinguishable from an unknown plugin on purpose.
        assertThat(rest.getForEntity("/api/plugins/good/blob", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/plugins/nope/blob", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void answers404ForARefThatIsNotThisPluginsAndForNonsense() {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            String ref = refOf(upload(PNG, "diagram.png", "image/png", session));

            // A well-formed ref belonging to this plugin, asked for under another plugin's path. The other
            // plugin declares no blobs, so this is 404 twice over — but the point stands for any pair: a ref
            // is resolved inside a namespace, never globally.
            assertThat(rest.getForEntity("/api/plugins/good/blob/" + ref, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            // Not a UUID at all: still a 404 rather than a 500 from a parse deeper in.
            assertThat(rest.getForEntity(BASE + "/not-a-ref", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            deleteAll();
        }
    }

    @Test
    void purgingAPluginTakesItsFilesWithIt() {
        // §7.8: purge is the explicit, irreversible admin action, and files are the third store a plugin can
        // write to. A purge that left them behind would be the half-purge the schema work already called
        // out — an admin who asked for the data to be gone would still be hosting the uploads.
        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        String ref = refOf(upload(PNG, "diagram.png", "image/png", podcaster));
        assertThat(rest.getForEntity(BASE + "/" + ref, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, admin.session() + "; " + admin.xsrf());
        headers.add("X-XSRF-TOKEN", admin.token());
        ResponseEntity<String> purge = rest.exchange("/api/admin/plugins/blobs/purge", HttpMethod.POST,
                new HttpEntity<>("", headers), String.class);

        assertThat(purge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity(BASE + "/" + ref, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(JSON.readTree(get(BASE, null).getBody()).path("totalElements").asLong()).isZero();
    }

    @Test
    void storesTheSniffedTypeRatherThanTheDeclaredOne() {
        // Both are allowed by the fixture, so nothing refuses this — what is asserted is that the *bytes*
        // decide what is stored and later served, not the header the client wrote.
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        try {
            ResponseEntity<String> created = upload(GIF, "labelled.png", "image/png", session);

            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(JSON.readTree(created.getBody()).path("mime").asString()).isEqualTo("image/gif");
        } finally {
            deleteAll();
        }
    }
}
