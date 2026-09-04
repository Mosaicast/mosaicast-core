// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link Notification} (ARCHITECTURE §17). */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** One user's inbox, newest first — paged, like every list endpoint (§13). */
    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    /** What the bell shows. Backed by the partial index, so it does not walk a user's history. */
    long countByUserIdAndReadAtIsNull(UUID userId);

    /** One row, scoped to its owner — so a caller cannot mark somebody else's notification read. */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    /** Marks a user's whole inbox read in one statement, rather than loading it to set a timestamp. */
    @Modifying
    @Transactional
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Everything addressed to one user, dropped when the account is erased (§17.2, §12.8). */
    @Transactional
    long deleteByUserId(UUID userId);

    /**
     * Everything one plugin sent, dropped when that plugin's data goes (§17.2).
     *
     * <p>Matched on the {@code plugin:<id>} source rather than a foreign key, because a notification
     * outlives the row that prompted it and core never learned which of a plugin's documents that was.
     */
    @Transactional
    long deleteBySource(String source);

    /** Read notifications older than {@code cutoff} — the retention sweep (§17.2). */
    @Modifying
    @Transactional
    @Query("delete from Notification n where n.readAt is not null and n.readAt < :cutoff")
    int deleteReadBefore(@Param("cutoff") Instant cutoff);

    /**
     * The ids of a user's unread notifications beyond the newest {@code keep}, oldest first.
     *
     * <p>Feeds the per-user cap (§17.2): an inbox nobody empties is not a feature, and an unbounded one is
     * a table that only grows. Trimming the <em>oldest</em> unread rather than refusing the newest means a
     * user who ignores their bell still sees what just happened.
     */
    @Query("""
            select n.id from Notification n
            where n.userId = :userId and n.readAt is null
            order by n.createdAt asc, n.id asc
            """)
    List<UUID> unreadOldestFirst(@Param("userId") UUID userId, Pageable pageable);
}
