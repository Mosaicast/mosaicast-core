// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the identity layer ({@link EpisodeRef}). */
public interface EpisodeRefRepository extends JpaRepository<EpisodeRef, UUID> {

    /** Looks up a ref by its feed-scoped GUID — the reconciler's primary match (§5.2). */
    Optional<EpisodeRef> findByFeedIdAndExternalGuid(UUID feedId, String externalGuid);

    /** All refs currently in a status for a feed (e.g. PLANNED binding candidates, §5.3). */
    List<EpisodeRef> findByFeedIdAndStatus(UUID feedId, EpisodeStatus status);

    /** Every ref of a feed, regardless of status — used to detect items that vanished (§5.2 case 3). */
    List<EpisodeRef> findByFeedId(UUID feedId);

    /** Count of refs in a feed (for admin feed listings). */
    long countByFeedId(UUID feedId);

    /** Distinct seasons present in a feed (a season is "all refs with season=N", §4.4). */
    @Query("""
            select distinct e.season from EpisodeRef e
            where e.feedId = :feedId and e.season is not null and e.status <> 'WITHDRAWN'
            order by e.season
            """)
    List<Integer> findSeasons(@Param("feedId") UUID feedId);

    /**
     * Visible episodes of a feed, optionally filtered by season, in canonical order (§6.2): season then
     * episode number, nulls last. WITHDRAWN items are excluded from listings.
     */
    @Query("""
            select e from EpisodeRef e
            where e.feedId = :feedId and e.status <> 'WITHDRAWN'
              and (:season is null or e.season = :season)
            order by e.season asc nulls last, e.episodeNo asc nulls last, e.firstSeenAt desc
            """)
    Page<EpisodeRef> findVisible(@Param("feedId") UUID feedId, @Param("season") Integer season, Pageable pageable);
}
