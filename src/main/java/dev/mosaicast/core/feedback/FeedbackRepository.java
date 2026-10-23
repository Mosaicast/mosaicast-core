// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    /** The inbox, newest first, optionally narrowed by status and kind (null means any). */
    @Query("""
            select f from Feedback f
            where (:status is null or f.status = :status) and (:kind is null or f.kind = :kind)
            order by f.createdAt desc, f.id desc
            """)
    Page<Feedback> inbox(@Param("status") String status, @Param("kind") String kind, Pageable pageable);

    /** Whether an identical report arrived since {@code since} — the dedup check (§20.4). */
    boolean existsByDedupHashAndCreatedAtAfter(String dedupHash, Instant since);

    /** Unhandled anonymous reports — what the anonymous ceiling counts (§20.4). */
    @Query("select count(f) from Feedback f where f.userId is null and f.status in ('new', 'seen')")
    long countOpenAnonymous();

    @Query("select count(f) from Feedback f where f.userId is null and f.createdAt > :since")
    long countAnonymousSince(@Param("since") Instant since);

    List<Feedback> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Feedback> findByIdIn(Collection<UUID> ids);

    long deleteByUserId(UUID userId);

    /** Closed reports past retention (§20.5). Open ones stay until handled. */
    @Modifying
    @Query("delete from Feedback f where f.status in ('done', 'spam') and f.handledAt < :cutoff")
    int purgeClosedBefore(@Param("cutoff") Instant cutoff);
}
