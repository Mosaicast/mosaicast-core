// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.core.support.DevLogin;
import dev.mosaicast.plugin.api.Criteria;
import java.time.Instant;
import java.util.Map;
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
 * The schema read surface end to end (ARCHITECTURE §7.6): a real plugin declaring entities, provisioned by
 * the host, queried the way a plugin's Web Component queries it — over HTTP, with the manifest's own access
 * floor in front.
 *
 * <p>The cases that matter are the refusals. A name the manifest does not declare must come back as a 400
 * or a 404 and never as a 500, a doc-store plugin must have no schema address at all, and the read floor
 * must apply here exactly as it does on the doc surface — one rule, two surfaces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
@Testcontainers
class PluginSchemaControllerIntegrationTest {

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
    private PluginLoaderService plugins;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "/api/plugins/wikifix/schema/page";

    /**
     * Rows beyond the one {@code register(ctx)} seeds, written through the plugin's own store.
     *
     * <p>Through the store rather than through SQL on purpose: the surface under test reads what a plugin's
     * backend wrote, so the fixture data should arrive the way a plugin's data actually does.
     */
    @BeforeEach
    void seedPages() {
        SchemaStoreImpl schema = plugins.schemaOf("wikifix").orElseThrow();
        if (schema.count("page", Criteria.all()) > 1) {
            return;
        }
        schema.insert("page", Map.of(
                "slug", "kraken", "title", "The Kraken", "markdown", "A very big squid in the fog.",
                "views", 30L, "rating", 4.0, "published", true,
                "updatedAt", Instant.parse("2026-04-01T10:00:00Z")));
        schema.insert("page", Map.of(
                "slug", "draft-squid", "title", "Draft", "markdown", "Squid notes, unfinished.",
                "views", 0L, "rating", 1.0, "published", false,
                "updatedAt", Instant.parse("2026-04-02T10:00:00Z")));
    }

    // ---- reading ----

    @Test
    void listsRowsInThePagedEnvelopeTheDocSurfaceUses() {
        JsonNode body = ok(BASE);

        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(50);
        assertThat(body.get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void serializesEachDeclaredTypeAsItsJsonCounterpart() {
        JsonNode row = ok(BASE + "?where=slug:eq:seeded").get("items").get(0);

        assertThat(row.get("id").asLong()).isPositive();
        assertThat(row.get("title").asString()).isEqualTo("Seeded page");
        assertThat(row.get("views").asLong()).isEqualTo(7);
        assertThat(row.get("rating").asDouble()).isEqualTo(4.5);
        assertThat(row.get("published").asBoolean()).isTrue();
        // An instant, not epoch millis — SchemaStoreImpl normalizes the JDBC Timestamp before mapping.
        assertThat(row.get("updatedAt").asString()).startsWith("2026-03-04T10:00:00");
    }

    @Test
    void filtersOnEveryDeclaredType() {
        assertThat(slugs(BASE + "?where=published:eq:false")).containsExactly("draft-squid");
        assertThat(slugs(BASE + "?where=views:gte:7&orderBy=views:asc")).containsExactly("seeded", "kraken");
        assertThat(slugs(BASE + "?where=rating:lt:4")).containsExactly("draft-squid");
        assertThat(slugs(BASE + "?where=updatedAt:gte:2026-04-02T00:00:00Z")).containsExactly("draft-squid");
        assertThat(slugs(BASE + "?where=slug:in:kraken,seeded&orderBy=slug:asc"))
                .containsExactly("kraken", "seeded");
        assertThat(slugs(BASE + "?where=markdown:isnotnull")).hasSize(3);
    }

    @Test
    void ordersAndPages() {
        JsonNode first = ok(BASE + "?orderBy=views:desc&page=0&size=1");
        JsonNode second = ok(BASE + "?orderBy=views:desc&page=1&size=1");

        assertThat(first.get("items").get(0).get("slug").asString()).isEqualTo("kraken");
        assertThat(second.get("items").get(0).get("slug").asString()).isEqualTo("seeded");
        // The total is the unpaged count, so a client knows there is more than the page it holds.
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void clampsThePageSizeInsteadOfServingEverything() {
        assertThat(ok(BASE + "?size=10000").get("size").asInt()).isEqualTo(200);
        assertThat(ok(BASE + "?size=0").get("size").asInt()).isEqualTo(1);
        assertThat(ok(BASE + "?page=-3").get("page").asInt()).isZero();
    }

    @Test
    void countsWithoutFetching() {
        assertThat(ok(BASE + "/count").get("count").asLong()).isEqualTo(3);
        assertThat(ok(BASE + "/count?where=published:eq:true").get("count").asLong()).isEqualTo(2);
    }

    @Test
    void findsOneRowByItsPlatformAssignedId() {
        long id = ok(BASE + "?where=slug:eq:kraken").get("items").get(0).get("id").asLong();

        assertThat(ok(BASE + "/" + id).get("slug").asString()).isEqualTo("kraken");
        assertThat(status(BASE + "/999999")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- search ----

    @Test
    void searchesTheProvisionedFullTextIndex() {
        JsonNode hits = ok(BASE + "/search?field=markdown&q=squid");

        assertThat(hits.get("items")).hasSize(2);
        assertThat(hits.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void appliesCriteriaOnTopOfTheMatch() {
        JsonNode hits = ok(BASE + "/search?field=markdown&q=squid&where=published:eq:true");

        assertThat(hits.get("items")).hasSize(1);
        assertThat(hits.get("items").get(0).get("slug").asString()).isEqualTo("kraken");
        // Counted with the same match clause, not derived from the page's own length.
        assertThat(hits.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void matchesNothingOnAnEmptyQuery() {
        // What a search box wants while the user has typed nothing — everything would be the wrong answer.
        JsonNode hits = ok(BASE + "/search?field=markdown&q=");

        assertThat(hits.get("items")).isEmpty();
        assertThat(hits.get("totalElements").asLong()).isZero();
    }

    @Test
    void refusesSearchOnAFieldWithNoFullTextIndex() {
        assertThat(status(BASE + "/search?field=title&q=kraken")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- refusals ----

    @Test
    void anEntityTheManifestDoesNotDeclareIsAnAddressThatDoesNotExist() {
        // 404 rather than the store's 400: over HTTP the entity is a path segment, and a path segment
        // naming nothing is an address, not a parameter.
        assertThat(status("/api/plugins/wikifix/schema/pages")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUndeclaredFieldIsAMalformedRequest() {
        assertThat(status(BASE + "?where=secret:eq:1")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(BASE + "?orderBy=secret:asc")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(BASE + "?where=views:eq:many")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(BASE + "?where=slug:contains:x")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDocStorePluginHasNoSchemaAddressAtAll() {
        // `good` is loaded and readable anonymously — it simply declares no schema.
        assertThat(status("/api/plugins/good/schema/page")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownOrRejectedPluginIsA404() {
        assertThat(status("/api/plugins/nope/schema/page")).isEqualTo(HttpStatus.NOT_FOUND);
        // `schema` declares "schema" storage with no entities and is rejected at load.
        assertThat(status("/api/plugins/schema/schema/page")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDeclaredReadFloorGovernsThisSurfaceToo() {
        String locked = "/api/plugins/wikilocked/schema/page";

        // Same manifest rule as the doc surface: `wikilocked` declares readableBy: podcaster.
        assertThat(status(locked)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(locked + "/count")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(locked + "/search?field=markdown&q=squid")).isEqualTo(HttpStatus.FORBIDDEN);

        DevLogin.Cookies podcaster = DevLogin.login(rest, "podcaster");
        assertThat(get(locked, podcaster).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers ----

    private JsonNode ok(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        // The body carries the problem detail, which is the whole diagnosis when this fails.
        assertThat(response.getStatusCode()).as("GET %s → %s", path, response.getBody())
                .isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private HttpStatus status(String path) {
        return HttpStatus.valueOf(rest.getForEntity(path, String.class).getStatusCode().value());
    }

    private ResponseEntity<String> get(String path, DevLogin.Cookies cookies) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.session() + "; " + cookies.xsrf());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private java.util.List<String> slugs(String path) {
        java.util.List<String> slugs = new java.util.ArrayList<>();
        ok(path).get("items").forEach(row -> slugs.add(row.get("slug").asString()));
        return slugs;
    }
}
