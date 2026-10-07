// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Postgres backend reads and writes without holding a whole blob in heap: bytes stream in
 * {@link PostgresBlobStore#CHUNK_BYTES} pieces, a range touches only its chunks, and a blob replaced during a
 * read fails that read rather than splicing two versions together.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class PostgresBlobStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void pluginsDir(DynamicPropertyRegistry registry) {
        registry.add("mosaicast.plugins-dir", () -> System.getProperty("mosaicast.test.plugins-dir"));
    }

    private static final int CHUNK = PostgresBlobStore.CHUNK_BYTES;

    @Autowired
    private PostgresBlobStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aBlobLargerThanSeveralChunksRoundTripsByteForByte() throws IOException {
        byte[] bytes = random(3 * CHUNK + 12_345);
        String key = UUID.randomUUID().toString();

        BlobRef ref = store.put("test", key, new ByteArrayInputStream(bytes), "application/zip", "a.zip",
                null);

        BlobContent content = store.get(ref);
        assertThat(content.totalSize()).isEqualTo(bytes.length);
        try (InputStream in = content.stream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(store.stat(ref)).get().satisfies(meta -> {
            assertThat(meta.size()).isEqualTo(bytes.length);
            assertThat(meta.filename()).isEqualTo("a.zip");
            assertThat(meta.mime()).isEqualTo("application/zip");
        });
    }

    @Test
    void aRangeAcrossAChunkBoundaryIsExactlyThatSlice() throws IOException {
        byte[] bytes = random(2 * CHUNK + 100);
        BlobRef ref = store.put("test", UUID.randomUUID().toString(), new ByteArrayInputStream(bytes),
                "audio/mpeg");

        long start = CHUNK - 10;
        long end = CHUNK + 10;
        try (InputStream in = store.getRange(ref, start, end).stream()) {
            assertThat(in.readAllBytes()).isEqualTo(Arrays.copyOfRange(bytes, (int) start, (int) end + 1));
        }
        // Past the end clamps, as before.
        try (InputStream in = store.getRange(ref, bytes.length - 5, bytes.length + 100).stream()) {
            assertThat(in.readAllBytes()).hasSize(5);
        }
    }

    @Test
    void writingTheSameKeyAgainReplacesTheBytesAndKeepsTheId() throws IOException {
        String key = UUID.randomUUID().toString();
        BlobRef first = store.put("test", key, new ByteArrayInputStream(random(500)), "image/png");
        byte[] second = random(800);

        BlobRef again = store.put("test", key, new ByteArrayInputStream(second), "image/webp");

        assertThat(again).isEqualTo(first);
        assertThat(store.stat("test", key)).get().satisfies(meta -> {
            assertThat(meta.size()).isEqualTo(800);
            assertThat(meta.mime()).isEqualTo("image/webp");
        });
        try (InputStream in = store.get(again).stream()) {
            assertThat(in.readAllBytes()).isEqualTo(second);
        }
    }

    @Test
    void aBlobReplacedHalfwayThroughAReadFailsThatReadRatherThanMixingVersions() throws Exception {
        String key = UUID.randomUUID().toString();
        BlobRef ref = store.put("test", key, new ByteArrayInputStream(random(2 * CHUNK)), "application/zip");
        InputStream reading = store.get(ref).stream();
        assertThat(reading.readNBytes(CHUNK)).hasSize(CHUNK);

        // updated_at must move for the pin to see it; now() is per transaction, and these are separate ones.
        Thread.sleep(5);
        store.put("test", key, new ByteArrayInputStream(random(2 * CHUNK)), "application/zip");

        assertThatThrownBy(reading::readAllBytes).isInstanceOf(IOException.class)
                .hasMessageContaining("replaced or deleted");
    }

    @Test
    void theColumnIsStoredOutOfLineAndUncompressed() {
        // EXTERNAL ('e') is what makes a substring read only the TOAST chunks it covers.
        String storage = jdbc.queryForObject("""
                SELECT attstorage::text FROM pg_attribute
                WHERE attrelid = 'blob'::regclass AND attname = 'data'
                """, String.class);
        assertThat(storage).isEqualTo("e");
    }

    @Test
    void aMigrationWriteKeepsTheIdItWasGiven() throws IOException {
        UUID id = UUID.randomUUID();
        byte[] bytes = random(CHUNK + 1);
        BlobMetadata meta = new BlobMetadata(new BlobRef(id, "test"), "kept-" + id, "image/png", bytes.length,
                java.time.Instant.now(), "kept.png");

        BlobRef ref = store.putVerbatim(meta, new ByteArrayInputStream(bytes));

        assertThat(ref.id()).isEqualTo(id);
        try (InputStream in = store.get(ref).stream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        store.delete(ref);
        assertThat(store.stat(ref)).isEmpty();
    }

    private static byte[] random(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }
}
