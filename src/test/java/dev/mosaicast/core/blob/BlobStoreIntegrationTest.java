// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Risk-point test for the BlobStore (ARCHITECTURE §11, §13.5): store a blob and read exact byte ranges
 * (server-side {@code substring}), including a range that runs past the end (clamped). Runs against a real
 * Postgres so the BYTEA range read is exercised for real.
 */
@SpringBootTest
@Testcontainers
class BlobStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private BlobStore blobStore;

    @Test
    void storesAndReadsRanges() throws Exception {
        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        BlobRef ref = blobStore.put("test", "k1", new ByteArrayInputStream(data), "application/octet-stream");

        // Full read.
        BlobContent full = blobStore.get(ref);
        assertThat(full.totalSize()).isEqualTo(100);
        assertThat(full.stream().readAllBytes()).isEqualTo(data);

        // Exact middle range [10, 19] → 10 bytes, values 10..19.
        BlobContent range = blobStore.getRange(ref, 10, 19);
        byte[] slice = range.stream().readAllBytes();
        assertThat(slice).hasSize(10);
        assertThat(slice[0]).isEqualTo((byte) 10);
        assertThat(slice[9]).isEqualTo((byte) 19);
        assertThat(range.totalSize()).isEqualTo(100);

        // Range past the end is clamped to the last byte.
        byte[] tail = blobStore.getRange(ref, 95, 200).stream().readAllBytes();
        assertThat(tail).hasSize(5);
        assertThat(tail[4]).isEqualTo((byte) 99);

        // Metadata without bytes.
        assertThat(blobStore.stat("test", "k1")).hasValueSatisfying(meta -> {
            assertThat(meta.size()).isEqualTo(100);
            assertThat(meta.mime()).isEqualTo("application/octet-stream");
        });
    }

    @Test
    void putReplacesExistingKey() throws Exception {
        blobStore.put("test", "k2", new ByteArrayInputStream(new byte[] {1, 2, 3}), "text/plain");
        BlobRef ref = blobStore.put("test", "k2", new ByteArrayInputStream(new byte[] {9, 9}), "text/plain");

        assertThat(blobStore.get(ref).stream().readAllBytes()).containsExactly(9, 9);
    }
}
