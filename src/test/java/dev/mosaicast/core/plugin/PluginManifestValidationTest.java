// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PluginManifest} parsing and validation (ARCHITECTURE §7.2) — the stability anchor:
 * a compatible manifest passes; an incompatible {@code platformApi}, declared schema, or unknown slot
 * placement is rejected (so the loader disables that plugin, §7.8).
 */
class PluginManifestValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compatibleManifestValidates() throws Exception {
        PluginManifest manifest = parse("""
                {"id":"sample","version":"1.0.0","platformApi":"0.4.0","name":"Sample",
                 "backend":{"basePath":"/api/plugins/sample","extensions":["X"]},
                 "frontend":{"entry":"s.js","elements":["s-card"]},
                 "slots":[{"scope":"site","element":"s-card","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc","config":{},"consent":{"categories":[],"externalSources":[]}}
                """);
        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.id()).isEqualTo("sample");
    }

    @Test
    void patchDifferenceIsCompatible() throws Exception {
        // Same major.minor as the host (0.4.x), different patch — accepted.
        assertThatCode(parse(base("0.4.7", "doc", "sidebar"))::validate).doesNotThrowAnyException();
    }

    @Test
    void incompatiblePlatformApiIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.2.0", "doc", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("platformApi");
    }

    @Test
    void declaredSchemaStorageIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.4.0", "schema", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void unknownSlotPlacementIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.4.0", "doc", "nowhere"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("placement");
    }

    @Test
    void declaredConfigFieldsAreAccepted() throws Exception {
        assertThatCode(parse(withConfig("""
                {"minutes":{"type":"number","default":30,"editableBy":"podcaster"},
                 "label":{"type":"string","default":"hi"},
                 "loud":{"type":"boolean","default":true,"editableBy":"admin"}}
                """))::validate).doesNotThrowAnyException();
    }

    @Test
    void unknownConfigTypeIsRejected() throws Exception {
        // The host renders the form and type-checks admin input from this declaration, so a type it cannot
        // render is a load-time rejection rather than a broken admin page.
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"colour\",\"default\":\"red\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("unknown type");
    }

    @Test
    void unknownEditableByIsRejected() throws Exception {
        assertThatThrownBy(parse(withConfig(
                "{\"x\":{\"type\":\"string\",\"default\":\"a\",\"editableBy\":\"fan\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("editableBy");
    }

    @Test
    void defaultMustMatchDeclaredType() throws Exception {
        assertThatThrownBy(parse(withConfig("{\"x\":{\"type\":\"number\",\"default\":\"thirty\"}}"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("default");
    }

    @Test
    void fieldWithoutEditableByDefaultsToAdmin() throws Exception {
        PluginManifest manifest = parse(withConfig("{\"x\":{\"type\":\"string\",\"default\":\"a\"}}"));
        assertThatCode(manifest::validate).doesNotThrowAnyException();
        assertThat(manifest.config().get("x").editableByOrDefault())
                .isEqualTo(PluginManifest.EDITABLE_BY_ADMIN);
    }

    private PluginManifest parse(String json) throws Exception {
        return mapper.readValue(json, PluginManifest.class);
    }

    private static String base(String platformApi, String storage, String placement) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"%s","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"%s","visibleTo":"anonymous"}],
                 "storage":"%s","config":{},"consent":{"categories":[],"externalSources":[]}}
                """.formatted(platformApi, placement, storage);
    }

    /** A valid manifest carrying the given {@code config} block, to isolate config validation. */
    private static String withConfig(String config) {
        return """
                {"id":"p","version":"1.0.0","platformApi":"0.4.0","name":"P",
                 "slots":[{"scope":"site","element":"e","placement":"sidebar","visibleTo":"anonymous"}],
                 "storage":"doc","config":%s,"consent":{"categories":[],"externalSources":[]}}
                """.formatted(config);
    }
}
