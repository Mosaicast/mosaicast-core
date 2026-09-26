// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.java.spi.JavaTypeBasicAdaptor;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the JSONB encoding across the Jackson 2 → 3 move (ARCHITECTURE §4.2).
 *
 * <p>Every row in {@code episode_display.snapshot} and {@code plugin_data.value} was written by Hibernate's
 * Jackson 2 mapper. Jackson 3 defaults to a <em>different</em> encoding for temporal values — ISO-8601
 * strings instead of numbers — and both forms read back fine, so a regression here would be silent: new rows
 * would simply start looking different from old ones, and anything reading the column outside Hibernate would
 * have to cope with two encodings forever.
 *
 * <p>The fixtures in {@code src/test/resources/premigration/} are <strong>real rows</strong>, dumped from a
 * running Spring Boot 3.4 / Jackson 2 instance before the upgrade. They are the only trustworthy oracle for
 * "what the old mapper produced", which is why they are checked in rather than generated.
 */
class Jackson3JsonFormatMapperTest {

    private final Jackson3JsonFormatMapper mapper = new Jackson3JsonFormatMapper();
    private final JsonMapper json = JsonMapper.builder().build();

    /** Hibernate hands the mapper a {@link JavaType}; this is the minimum needed to stand in for one. */
    private static <T> JavaType<T> javaType(Class<T> type) {
        return new JavaTypeBasicAdaptor<>(type);
    }

    @Test
    void readsEveryDisplaySnapshotWrittenByJackson2() {
        for (String row : fixture("premigration/episode-display-jackson2.jsonl")) {
            DisplaySnapshot snapshot = mapper.fromString(row, javaType(DisplaySnapshot.class), null);

            assertThat(snapshot.title()).isNotBlank();
            // The two temporals are the whole point: numeric epoch seconds and numeric duration seconds.
            assertThat(snapshot.publishedAt()).isNotNull().isAfter(Instant.EPOCH);
            assertThat(snapshot.duration()).isNotNull().isGreaterThan(Duration.ZERO);
        }
    }

    @Test
    void reWritingAPreMigrationRowKeepsItsEncoding() {
        for (String row : fixture("premigration/episode-display-jackson2.jsonl")) {
            DisplaySnapshot snapshot = mapper.fromString(row, javaType(DisplaySnapshot.class), null);
            String rewritten = mapper.toString(snapshot, javaType(DisplaySnapshot.class), null);

            // Compare parsed trees, not text: jsonb does not preserve key order, so the bytes coming back
            // out of Postgres are already reordered. What must not change is the *shape* of each value.
            // The one permitted difference: SDK 0.16.0 added `descriptionText`, which an old row gains (as a
            // string) the first time it is written back. Every value the row already had stays as it was.
            tools.jackson.databind.node.ObjectNode written =
                    (tools.jackson.databind.node.ObjectNode) json.readTree(rewritten);
            JsonNode added = written.remove("descriptionText");
            assertThat(added != null && added.isString()).as("descriptionText written as a string").isTrue();
            assertThat(written).isEqualTo(json.readTree(row));

            JsonNode tree = json.readTree(rewritten);
            assertThat(tree.get("publishedAt").isNumber())
                    .as("publishedAt must stay a number — ISO-8601 would split the column into two encodings")
                    .isTrue();
            assertThat(tree.get("duration").isNumber())
                    .as("duration must stay a number, not \"PT58M2S\"")
                    .isTrue();
        }
    }

    @Test
    void nullFieldsAreWrittenNotOmitted() {
        // The Jackson 2 mapper wrote `"imageUrl": null`; dropping nulls would change every row it touches.
        DisplaySnapshot snapshot = new DisplaySnapshot(
                "t", "d", null, Instant.ofEpochSecond(1_781_416_800L), Duration.ofSeconds(3482),
                null, null, null, null);

        JsonNode tree = json.readTree(mapper.toString(snapshot, javaType(DisplaySnapshot.class), null));

        assertThat(tree.has("imageUrl")).isTrue();
        assertThat(tree.get("imageUrl").isNull()).isTrue();
    }

    @Test
    void rawTreeColumnsRoundTrip() {
        // plugin_data.value / plugin_config.value / app_log.context are stored as trees, not POJOs.
        for (String row : fixture("premigration/plugin-data-jackson2.jsonl")) {
            JsonNode node = mapper.fromString(row, javaType(JsonNode.class), null);
            String rewritten = mapper.toString(node, javaType(JsonNode.class), null);

            assertThat(json.readTree(rewritten)).isEqualTo(json.readTree(row));
        }
    }

    @Test
    void anUnknownFieldFromANewerBuildDoesNotBreakTheRead() {
        // Forward compatibility: a column written by a newer version must stay readable by an older one.
        String withExtra = "{\"title\":\"t\",\"description\":\"d\",\"somethingNew\":42}";

        DisplaySnapshot snapshot = mapper.fromString(withExtra, javaType(DisplaySnapshot.class), null);

        assertThat(snapshot.title()).isEqualTo("t");
    }

    private static List<String> fixture(String path) {
        try {
            String body = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return body.lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
