// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.SchemaStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The schema provider end to end (ARCHITECTURE §7.6): a real plugin folder declaring entities, provisioned
 * by the host's own migration runner into namespaced tables, driven through the SDK's {@link SchemaStore},
 * and dropped by purge.
 *
 * <p>Ordered, because the last test purges: it is the one operation whose whole point is that the tables
 * stop existing, and running it first would leave the rest asserting against nothing.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    @Autowired
    private PluginLoaderService plugins;

    @Autowired
    private PluginSchemaMigrator migrator;

    @Autowired
    private PluginSettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    /** The row type a plugin author would write — components named for the declared fields. */
    record Page(long id, String slug, String title, String markdown, Long views, Double rating,
                Boolean published, Instant updatedAt) {
    }

    private SchemaStore schema() {
        // The same object the plugin's register(ctx) was handed.
        Map<String, PluginSchemaValidator.Entity> entities = plugins.active("wikifix")
                .map(PluginRegistration::manifest)
                .orElseThrow(() -> new AssertionError("wikifix plugin not loaded"))
                .schemaEntities();
        return new SchemaStoreImpl("wikifix", entities, jdbc, new tools.jackson.databind.ObjectMapper());
    }

    @Test
    @Order(1)
    void theDeclaredEntityIsProvisionedAsANamespacedTable() {
        assertThat(migrator.tablesOf("wikifix")).containsExactly("plugin_wikifix_page");
        assertThat(schema().namespace()).isEqualTo("plugin_wikifix_");
        assertThat(schema().entities()).containsExactly("page");
    }

    @Test
    @Order(2)
    void theDeclaredIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where tablename = 'plugin_wikifix_page'", String.class);

        // One per modifier the manifest declared: unique on slug, plain on updatedAt, GIN on markdown.
        assertThat(indexes).anyMatch(name -> name.contains("slug") && name.endsWith("uq"));
        assertThat(indexes).anyMatch(name -> name.contains("updatedat") && name.endsWith("ix"));
        assertThat(indexes).anyMatch(name -> name.contains("markdown") && name.endsWith("fts"));
    }

    @Test
    @Order(3)
    void thePluginsOwnSeedRowRoundTripsThroughTheStore() {
        // Written by FixturePlugin inside register(ctx) — so the tables existed before the plugin ran.
        List<Page> pages = schema().select("page",
                Criteria.where("slug", Criteria.Op.EQ, "seeded"), Page.class);

        assertThat(pages).hasSize(1);
        Page page = pages.get(0);
        assertThat(page.id()).isPositive();
        assertThat(page.title()).isEqualTo("Seeded page");
        assertThat(page.views()).isEqualTo(7L);
        assertThat(page.rating()).isEqualTo(4.5);
        assertThat(page.published()).isTrue();
        // camelCase survives the round trip: the column is quoted, so Postgres does not fold it to
        // `updatedat` and leave the record component null.
        assertThat(page.updatedAt()).isEqualTo(Instant.parse("2026-03-04T10:00:00Z"));
    }

    @Test
    @Order(4)
    void insertUpdateFindAndDeleteWork() {
        SchemaStore schema = schema();
        long id = schema.insert("page", Map.of(
                "slug", "crud", "title", "CRUD", "markdown", "body", "published", false));

        assertThat(schema.find("page", id, Page.class)).get()
                .extracting(Page::title).isEqualTo("CRUD");

        assertThat(schema.update("page", id, Map.of("title", "CRUD again"))).isEqualTo(1);
        assertThat(schema.find("page", id, Page.class)).get()
                .extracting(Page::title).isEqualTo("CRUD again");

        // Fields left out of an update keep their value.
        assertThat(schema.find("page", id, Page.class)).get()
                .extracting(Page::markdown).isEqualTo("body");

        assertThat(schema.delete("page", Criteria.where("slug", Criteria.Op.EQ, "crud"))).isEqualTo(1);
        assertThat(schema.find("page", id, Page.class)).isEmpty();
    }

    @Test
    @Order(5)
    void fullTextSearchUsesTheDeclaredField() {
        SchemaStore schema = schema();

        assertThat(schema.search("page", "markdown", "lighthouse", Criteria.all(), Page.class))
                .extracting(Page::slug).contains("seeded");
        assertThat(schema.search("page", "markdown", "helicopter", Criteria.all(), Page.class)).isEmpty();
        // The contract: an empty query matches nothing rather than everything.
        assertThat(schema.search("page", "markdown", "", Criteria.all(), Page.class)).isEmpty();
    }

    @Test
    @Order(6)
    void searchingAFieldThatIsNotFulltextIsARefusal() {
        assertThatThrownBy(() -> schema().search("page", "title", "x", Criteria.all(), Page.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared :fulltext");
    }

    @Test
    @Order(7)
    void criteriaCombineFilterOrderAndPaging() {
        SchemaStore schema = schema();
        schema.insert("page", Map.of("slug", "a1", "title", "Alpha", "views", 10L, "published", true));
        schema.insert("page", Map.of("slug", "a2", "title", "Beta", "views", 20L, "published", true));
        schema.insert("page", Map.of("slug", "a3", "title", "Gamma", "views", 30L, "published", false));
        try {
            List<Page> published = schema.select("page",
                    Criteria.where("published", Criteria.Op.EQ, true)
                            .and("views", Criteria.Op.GTE, 10L)
                            .orderBy("views", Criteria.Direction.DESC)
                            .limit(1),
                    Page.class);
            assertThat(published).extracting(Page::slug).containsExactly("a2");

            assertThat(schema.count("page", Criteria.where("slug", Criteria.Op.IN, List.of("a1", "a2", "a3"))))
                    .isEqualTo(3);
            assertThat(schema.select("page", Criteria.where("slug", Criteria.Op.LIKE, "a%")
                    .orderBy("slug", Criteria.Direction.ASC).offset(1), Page.class))
                    .extracting(Page::slug).containsExactly("a2", "a3");
            assertThat(schema.count("page", Criteria.where("rating", Criteria.Op.IS_NULL, null)))
                    .isPositive();
        } finally {
            schema.delete("page", Criteria.where("slug", Criteria.Op.LIKE, "a%"));
        }
    }

    @Test
    @Order(8)
    void anUndeclaredEntityOrFieldIsRefusedRatherThanReachingTheDatabase() {
        SchemaStore schema = schema();

        // "Not blocked, unsayable": there is no string a plugin can pass that names another table.
        assertThatThrownBy(() -> schema.select("plugin_data", Criteria.all(), Page.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> schema.select("page",
                Criteria.where("slug'; drop table plugin_wikifix_page; --", Criteria.Op.EQ, "x"), Page.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> schema.insert("page", Map.of("nope", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> schema.insert("page", Map.of("id", 1L, "slug", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned by the platform");

        // The table it tried to name is still there.
        assertThat(migrator.tablesOf("wikifix")).containsExactly("plugin_wikifix_page");
    }

    @Test
    @Order(9)
    void aValueThatLooksLikeSqlIsStoredAsText() {
        SchemaStore schema = schema();
        String hostile = "'; drop table plugin_wikifix_page; --";
        long id = schema.insert("page", Map.of("slug", "injection", "title", hostile));
        try {
            // Bound as a parameter, so it is a title rather than a statement.
            assertThat(schema.find("page", id, Page.class)).get()
                    .extracting(Page::title).isEqualTo(hostile);
            assertThat(migrator.tablesOf("wikifix")).containsExactly("plugin_wikifix_page");
        } finally {
            schema.delete("page", Criteria.where("slug", Criteria.Op.EQ, "injection"));
        }
    }

    @Test
    @Order(10)
    void reprovisioningAnUnchangedDeclarationIsANoOp() {
        // Every boot re-runs this; it must not fail on the second one.
        Map<String, PluginSchemaValidator.Entity> entities = plugins.active("wikifix")
                .map(PluginRegistration::manifest).orElseThrow().schemaEntities();

        migrator.provision("wikifix", entities);
        migrator.provision("wikifix", entities);

        assertThat(migrator.tablesOf("wikifix")).containsExactly("plugin_wikifix_page");
        assertThat(schema().count("page", Criteria.all())).isPositive();
    }

    @Test
    @Order(11)
    void aChangedFieldTypeIsRefusedRatherThanRetyped() {
        // Retyping a column that already holds data loses it, and doing that because a manifest changed
        // between two boots is not the runner's call to make.
        Map<String, PluginSchemaValidator.Entity> retyped = PluginSchemaValidator.resolve("wikifix",
                PluginStorage.schema(Map.of("page", Map.of("views", "string"))));

        assertThatThrownBy(() -> migrator.provision("wikifix", retyped))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("does not retype");
    }

    @Test
    @Order(12)
    void aNewFieldIsAddedToAnExistingTable() {
        Map<String, PluginSchemaValidator.Entity> grown = PluginSchemaValidator.resolve("wikifix",
                PluginStorage.schema(Map.of("page", new java.util.LinkedHashMap<>(Map.of(
                        "slug", "string:indexed:unique", "summary", "text")))));

        migrator.provision("wikifix", grown);

        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'plugin_wikifix_page'
                """, String.class)).contains("summary", "slug", "markdown");
    }

    @Test
    @Order(13)
    void purgeDropsTheProvisionedTablesAndForgetsThem() {
        settings.purgeData("wikifix");

        assertThat(migrator.tablesOf("wikifix")).isEmpty();
        Integer remaining = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = current_schema() and table_name = 'plugin_wikifix_page'
                """, Integer.class);
        assertThat(remaining).isZero();
    }
}
