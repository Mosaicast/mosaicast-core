// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for configured feed sources ({@link Feed}). */
public interface FeedRepository extends JpaRepository<Feed, UUID> {

    /** Enabled feeds the scheduler should poll. */
    List<Feed> findByEnabledTrue();

    /**
     * Loads a feed with a pessimistic write lock on its row, so concurrent polls of the same feed (a
     * scheduler tick and a "refresh now") serialize instead of both inserting the same GUID and hitting
     * {@code uq_episode_ref_feed_guid} (ARCHITECTURE §5.4). Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Feed f where f.id = :id")
    Optional<Feed> lockById(@Param("id") UUID id);
}
