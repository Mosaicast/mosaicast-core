// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Blob}, including a server-side byte-range read. */
public interface BlobRepository extends JpaRepository<Blob, UUID> {

    Optional<Blob> findByNamespaceAndKey(String namespace, String key);

    /**
     * Reads a byte range straight from Postgres ({@code substring} on the {@code BYTEA}) so a range request
     * never pulls the whole blob into memory. {@code from} is 1-indexed (Postgres convention); the args are
     * {@code int} because Postgres' {@code substring(bytea, int, int)} takes integers.
     */
    @Query(value = "SELECT substring(data FROM :from FOR :len) FROM blob WHERE id = :id", nativeQuery = true)
    byte[] readRange(@Param("id") UUID id, @Param("from") int from, @Param("len") int len);
}
