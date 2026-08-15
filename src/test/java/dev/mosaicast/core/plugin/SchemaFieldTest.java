// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The manifest type grammar (ARCHITECTURE §7.6) — the whole boundary between what a plugin author types
 * and what becomes DDL. Everything outside the closed sets has to refuse the plugin rather than be coerced
 * into something that runs.
 */
class SchemaFieldTest {

    @Test
    void parsesATypeWithItsModifiers() {
        SchemaField field = SchemaField.parse("slug", "string:indexed:unique");

        assertThat(field.type()).isEqualTo(SchemaField.Type.STRING);
        assertThat(field.unique()).isTrue();
        // Unique already provides an index; asking for a second one would be a duplicate.
        assertThat(field.indexed()).isFalse();
    }

    @Test
    void everyDocumentedSpecFromTheSdkParses() {
        // These four are the SDK's own example. If the host stops accepting them, every plugin author
        // following the documentation is broken.
        assertThat(SchemaField.parse("slug", "string:indexed:unique").unique()).isTrue();
        assertThat(SchemaField.parse("title", "string").type()).isEqualTo(SchemaField.Type.STRING);
        assertThat(SchemaField.parse("markdown", "text:fulltext").fulltext()).isTrue();
        assertThat(SchemaField.parse("updatedAt", "timestamp:indexed").indexed()).isTrue();
    }

    @Test
    void unknownTypesAndModifiersAreRefusedWithTheKnownOnesNamed() {
        assertThatThrownBy(() -> SchemaField.parse("x", "jsonb"))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("unknown type")
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> SchemaField.parse("x", "string:clustered"))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("unknown modifier");
    }

    @Test
    void onlyTextCanBeFulltext() {
        assertThatThrownBy(() -> SchemaField.parse("n", "integer:fulltext"))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("only string and text");
    }

    @Test
    void aFieldNameCannotCarrySqlSyntax() {
        // The name is emitted into DDL as a quoted identifier. Nothing that could close the quote, and
        // nothing that could be read as a separate token, may match the pattern in the first place.
        for (String name : new String[] {
                "drop\"table", "has space", "has-hyphen", "semi;colon", "1leading", "", "with'quote",
        }) {
            assertThatThrownBy(() -> SchemaField.parse(name, "string"))
                    .as("field name %s", name)
                    .isInstanceOf(PluginValidationException.class);
        }
    }

    @Test
    void idIsThePlatformsAndCannotBeDeclared() {
        assertThatThrownBy(() -> SchemaField.parse("id", "integer"))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("assigned by the platform");
    }

    @Test
    void anEntityNameCarryingSqlIsRefusedToo() {
        PluginStorage storage = PluginStorage.schema(Map.of("page\"; drop table x --", Map.of("a", "string")));

        assertThatThrownBy(() -> PluginSchemaValidator.resolve("wiki", storage))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("entity name");
    }

    @Test
    void aSchemaDeclaringNoEntitiesIsRefusedRatherThanTreatedAsDoc() {
        // The old manifest shape — a bare "schema" string — declares nothing, so a plugin using it would
        // get no tables and no SchemaStore while believing it had both.
        assertThatThrownBy(() -> PluginSchemaValidator.resolve(
                        "wiki", new PluginStorage(PluginStorage.SCHEMA, Map.of())))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("no entities");
    }

    @Test
    void fieldsDifferingOnlyInCaseAreRefused() {
        PluginStorage storage = PluginStorage.schema(
                Map.of("page", new java.util.LinkedHashMap<>(Map.of("slug", "string", "Slug", "string"))));

        assertThatThrownBy(() -> PluginSchemaValidator.resolve("wiki", storage))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("differ only in case");
    }

    @Test
    void resolvingProducesTheNamespacedTableName() {
        Map<String, PluginSchemaValidator.Entity> entities = PluginSchemaValidator.resolve(
                "wiki", PluginStorage.schema(Map.of("page", Map.of("slug", "string"))));

        assertThat(entities.get("page").tableName()).isEqualTo("plugin_wiki_page");
        assertThat(PluginSchemaValidator.namespace("wiki")).isEqualTo("plugin_wiki_");
    }

    @Test
    void aTableNameTooLongForPostgresIsRefusedRatherThanTruncated() {
        // Postgres truncates at 63 bytes silently, and two entities colliding after truncation would share
        // one table — a data-mixing bug that would look like the plugin's own.
        String longEntity = "e".repeat(39);
        PluginStorage storage = PluginStorage.schema(
                Map.of(longEntity, Map.of("a", "string")));

        assertThatThrownBy(() -> PluginSchemaValidator.resolve("a".repeat(30), storage))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("too long");
    }
}
