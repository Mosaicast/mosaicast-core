// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                {"id":"sample","version":"1.0.0","platformApi":"0.3.0","name":"Sample",
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
        // Same major.minor as the host (0.3.x), different patch — accepted.
        assertThatCode(parse(base("0.3.7", "doc", "sidebar"))::validate).doesNotThrowAnyException();
    }

    @Test
    void incompatiblePlatformApiIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.2.0", "doc", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("platformApi");
    }

    @Test
    void declaredSchemaStorageIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.3.0", "schema", "sidebar"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void unknownSlotPlacementIsRejected() throws Exception {
        assertThatThrownBy(parse(base("0.3.0", "doc", "nowhere"))::validate)
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("placement");
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
}
