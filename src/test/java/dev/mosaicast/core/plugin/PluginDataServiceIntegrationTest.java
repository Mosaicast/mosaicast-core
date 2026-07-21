// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.Scope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Persistence tests for the generic doc store over real Postgres JSONB (ARCHITECTURE §7.6): values
 * round-trip, keys are validated, delete is idempotent, prefix queries are keyed, and — critically — the
 * store is <strong>hard-scoped by plugin id</strong> so one plugin can never read another's data.
 */
// RANDOM_PORT (not NONE): the app's SecurityConfig wires HttpSecurity, which needs a servlet web context.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class PluginDataServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PluginDataService data;

    @Test
    void valueRoundTrips() {
        data.put("p1", Scope.site(), "greeting", "hello");
        data.put("p1", Scope.episode("e1"), "note", Map.of("text", "hi"));

        assertThat(data.get("p1", Scope.site(), "greeting", String.class)).contains("hello");
        assertThat(data.getRaw("p1", Scope.episode("e1"), "note"))
                .hasValueSatisfying(node -> assertThat(node.get("text").asText()).isEqualTo("hi"));
    }

    @Test
    void dataIsHardScopedByPlugin() {
        data.put("pA", Scope.site(), "k", Map.of("v", 1));
        data.put("pB", Scope.site(), "k", Map.of("v", 2));

        assertThat(data.getRaw("pA", Scope.site(), "k")).hasValueSatisfying(
                node -> assertThat(node.get("v").asInt()).isEqualTo(1));
        assertThat(data.getRaw("pB", Scope.site(), "k")).hasValueSatisfying(
                node -> assertThat(node.get("v").asInt()).isEqualTo(2));
        // A plugin id with no data sees nothing, even for the same scope/key.
        assertThat(data.getRaw("pC", Scope.site(), "k")).isEmpty();
    }

    @Test
    void invalidKeyIsRejected() {
        assertThatThrownBy(() -> data.put("p2", Scope.site(), "bad/key", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteIsIdempotent() {
        data.put("p3", Scope.site(), "k", "v");
        assertThat(data.delete("p3", Scope.site(), "k")).isTrue();
        assertThat(data.delete("p3", Scope.site(), "k")).isFalse();
        assertThat(data.getRaw("p3", Scope.site(), "k")).isEmpty();
    }

    @Test
    void prefixQueryIsKeyedAndScoped() {
        data.put("p4", Scope.site(), "mark:a", 1);
        data.put("p4", Scope.site(), "mark:b", 2);
        data.put("p4", Scope.site(), "other", 3);

        List<DocEntry> marks = data.query("p4", Scope.site(), "mark:");
        assertThat(marks).extracting(DocEntry::key).containsExactlyInAnyOrder("mark:a", "mark:b");
        assertThat(data.query("p4", Scope.site(), "")).hasSize(3);
    }
}
