// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.blob.BlobRepository;
import dev.mosaicast.core.blob.InMemoryBlobStore;
import dev.mosaicast.core.blob.NamedBlobStore;
import dev.mosaicast.core.support.DevLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
 * The whole plugin file surface against a backend that is <strong>not Postgres</strong> (ARCHITECTURE §11).
 *
 * <p>This is the test that makes §11's "one backend per namespace" a fact rather than an intention. An
 * abstraction with one implementation is an assertion; routing a namespace elsewhere and driving upload,
 * range read, listing, quota and purge over HTTP is the only way to know the seam holds.
 *
 * <p>It also pins what went wrong before: the plugin surface used to query {@code BlobRepository} directly
 * for listing, counting, quota and purge, so a non-Postgres backend would have accepted uploads and then
 * reported an empty library and zero usage — the quota would have been unenforced and nothing would have
 * failed loudly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginBlobBackendRoutingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
        // Every plugin's files go to the in-memory backend, matched by prefix — an operator cannot
        // enumerate plugins that are not installed yet, which is why prefix matching exists.
        registry.add("mosaicast.blobs.namespaces.plugin", () -> InMemoryBlobStore.NAME);
    }

    @TestConfiguration
    static class Backend {
        /**
         * Declared as the concrete type, not as {@link NamedBlobStore}: with two backends on the context
         * there is no single one to inject by interface, which is exactly the ambiguity a real second
         * backend introduces. Production is unaffected — {@code BlobStoreRouter} is {@code @Primary} for
         * {@code BlobStore}, and the router itself takes the backends as a {@code List}.
         */
        @Bean
        InMemoryBlobStore inMemoryBlobStore() {
            return new InMemoryBlobStore();
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private InMemoryBlobStore memory;

    @Autowired
    private BlobRepository postgresBlobs;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "/api/plugins/blobs/blob";

    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @AfterEach
    void clear() {
        memory.deleteNamespace("plugin/blobs");
    }

    private ResponseEntity<String> upload(String filename) {
        DevLogin.Cookies session = DevLogin.login(rest, "podcaster");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource part = new ByteArrayResource(PNG) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_PNG);
        body.add("file", new HttpEntity<>(part, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, session.session() + "; " + session.xsrf());
        headers.add("X-XSRF-TOKEN", session.token());
        return rest.exchange(BASE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void storesTheBytesInTheRoutedBackendAndNotInPostgres() {
        ResponseEntity<String> created = upload("diagram.png");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(memory.count("plugin/blobs")).isEqualTo(1);
        // The routing is real, not merely configured: nothing landed in the relational store.
        assertThat(postgresBlobs.countByNamespace("plugin/blobs")).isZero();
    }

    @Test
    void servesTheBytesAndARangeThroughTheApp() {
        String ref = JSON.readTree(upload("diagram.png").getBody()).path("ref").asString();

        ResponseEntity<byte[]> whole = rest.getForEntity(BASE + "/" + ref, byte[].class);
        assertThat(whole.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(whole.getBody()).isEqualTo(PNG);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RANGE, "bytes=0-9");
        ResponseEntity<byte[]> ranged =
                rest.exchange(BASE + "/" + ref, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(ranged.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(ranged.getBody()).hasSize(10);
    }

    @Test
    void countsUsageAgainstTheQuotaFromTheRoutedBackend() {
        // The regression this whole change is about: usage used to be a SQL sum over a table this backend
        // never writes to, so the quota would have read zero however much was stored.
        upload("a.png");
        upload("b.png");

        JsonNode quota = JSON.readTree(rest.getForEntity(BASE + "/quota", String.class).getBody());

        assertThat(quota.path("usedBytes").asLong()).isEqualTo(2L * PNG.length);
    }

    @Test
    void listsFromTheRoutedBackend() {
        upload("first.png");
        upload("second.png");

        JsonNode listing = JSON.readTree(rest.getForEntity(BASE + "?page=0&size=10", String.class).getBody());

        assertThat(listing.path("totalElements").asLong()).isEqualTo(2);
        assertThat(listing.path("items")).hasSize(2);
    }

    @Test
    void purgesTheRoutedBackend() {
        upload("diagram.png");
        DevLogin.Cookies admin = DevLogin.login(rest, "admin");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, admin.session() + "; " + admin.xsrf());
        headers.add("X-XSRF-TOKEN", admin.token());

        rest.exchange("/api/admin/plugins/blobs/purge?confirm=blobs", HttpMethod.POST, new HttpEntity<>("", headers),
                String.class);

        assertThat(memory.count("plugin/blobs")).isZero();
    }

    @Test
    void leavesBrandingOnTheDefaultBackend() {
        // Only `plugin` was routed away. A namespace with no rule keeps the default, which is the property
        // that lets an operator move one thing without moving everything.
        assertThat(rest.getForEntity("/branding/logo", byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(memory.count("branding")).isZero();
    }
}
