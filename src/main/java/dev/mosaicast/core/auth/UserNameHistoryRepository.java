// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link UserNameHistory}. */
public interface UserNameHistoryRepository extends JpaRepository<UserNameHistory, UUID> {

    /**
     * A user's names, newest first — paged, because a revert only ever needs the most recent handful and
     * §13 has list endpoints paginating from day one.
     */
    List<UserNameHistory> findByUserIdOrderBySetAtDescIdDesc(UUID userId, Pageable pageable);

    /** Everything a user has been called, dropped when the account is erased (§12.8). */
    @org.springframework.transaction.annotation.Transactional
    long deleteByUserId(UUID userId);

    /**
     * Drops entries older than {@code cutoff}, keeping the retention cap §8.6 requires.
     *
     * <p>Deliberately unconditional: the alternative — keeping the newest entry per user forever — would
     * mean a name someone abandoned years ago outlives the retention policy that exists to let them
     * abandon it. The current name lives on the user row, so nothing is lost by emptying the history.
     */
    @Modifying
    @Query("delete from UserNameHistory h where h.setAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
