// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Blob}, including a server-side byte-range read. */
public interface BlobRepository extends JpaRepository<Blob, UUID> {

    Optional<Blob> findByNamespaceAndKey(String namespace, String key);

    /**
     * Every blob in a namespace, newest first, <strong>without its bytes</strong>.
     *
     * <p>Projected into {@link BlobMetadata} in the query rather than loaded as entities: {@code data} is
     * declared {@code LAZY}, but a lazy {@code byte[]} needs bytecode enhancement to actually stay unread,
     * and listing a plugin's media library must not be a way to pull every file it has stored into heap.
     * Selecting the columns removes the question.
     *
     * @param namespace the namespace to list
     * @param pageable  paging; the sort is fixed by the query
     * @return the page's metadata, newest first
     */
    @Query("""
            SELECT new dev.mosaicast.core.blob.BlobMetadata(
                       new dev.mosaicast.core.blob.BlobRef(b.id, b.namespace),
                       b.key, b.mime, b.sizeBytes, b.updatedAt, b.filename)
            FROM Blob b WHERE b.namespace = :namespace ORDER BY b.updatedAt DESC, b.id DESC
            """)
    List<BlobMetadata> listByNamespace(@Param("namespace") String namespace, Pageable pageable);

    long countByNamespace(String namespace);

    /**
     * The total size of a namespace's blobs — what a per-plugin quota is checked against.
     *
     * @param namespace the namespace to total
     * @return the sum of sizes, zero when the namespace is empty
     */
    @Query("SELECT COALESCE(SUM(b.sizeBytes), 0) FROM Blob b WHERE b.namespace = :namespace")
    long sumSizeBytesByNamespace(@Param("namespace") String namespace);

    /**
     * Drops a whole namespace — what "purge plugin data" means for files (§7.8).
     *
     * @param namespace the namespace to empty
     * @return how many blobs were deleted
     */
    @Modifying
    @Query("DELETE FROM Blob b WHERE b.namespace = :namespace")
    int deleteByNamespace(@Param("namespace") String namespace);

    /**
     * Reads a byte range straight from Postgres ({@code substring} on the {@code BYTEA}) so a range request
     * never pulls the whole blob into memory. {@code from} is 1-indexed (Postgres convention); the args are
     * {@code int} because Postgres' {@code substring(bytea, int, int)} takes integers.
     */
    @Query(value = "SELECT substring(data FROM :from FOR :len) FROM blob WHERE id = :id", nativeQuery = true)
    byte[] readRange(@Param("id") UUID id, @Param("from") int from, @Param("len") int len);
}
