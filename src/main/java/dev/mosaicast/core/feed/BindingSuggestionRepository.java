// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for fuzzy PLANNED-binding {@link BindingSuggestion}s (ARCHITECTURE §5.3). */
public interface BindingSuggestionRepository extends JpaRepository<BindingSuggestion, UUID> {

    /** A feed's outstanding suggestions, strongest match first (for the podcaster's review list). */
    List<BindingSuggestion> findByFeedIdOrderBySimilarityDesc(UUID feedId);

    /** The dedup key — one suggestion per (feed, planned ref, feed item), so repeated polls don't pile up. */
    Optional<BindingSuggestion> findByFeedIdAndPlannedRefIdAndRawGuid(UUID feedId, UUID plannedRefId, String rawGuid);

    /** Clears every suggestion pointing at a planned ref once it is bound (or gone) — nothing left to confirm. */
    @Modifying
    @Query("delete from BindingSuggestion s where s.plannedRefId = :plannedRefId")
    void deleteByPlannedRefId(@Param("plannedRefId") UUID plannedRefId);

    /** Clears every suggestion for a feed item once it is claimed — the other planned candidates are moot. */
    @Modifying
    @Query("delete from BindingSuggestion s where s.feedId = :feedId and s.rawGuid = :rawGuid")
    void deleteByFeedIdAndRawGuid(@Param("feedId") UUID feedId, @Param("rawGuid") String rawGuid);
}
