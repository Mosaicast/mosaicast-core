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

    /** A publicly visible ref by id: not WITHDRAWN and belonging to an enabled feed (detail / adjacent). */
    @Query("""
            select e from EpisodeRef e
            where e.id = :id and e.status <> 'WITHDRAWN'
              and e.feedId in (select f.id from Feed f where f.enabled = true)
            """)
    Optional<EpisodeRef> findVisibleById(@Param("id") UUID id);

    /** Distinct seasons present in a feed (a season is "all refs with season=N", §4.4). */
    @Query("""
            select distinct e.season from EpisodeRef e
            where e.feedId = :feedId and e.season is not null and e.status <> 'WITHDRAWN'
              and e.feedId in (select f.id from Feed f where f.enabled = true)
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
              and e.feedId in (select f.id from Feed f where f.enabled = true)
            order by e.season asc nulls last, e.episodeNo asc nulls last, e.firstSeenAt desc
            """)
    Page<EpisodeRef> findVisible(@Param("feedId") UUID feedId, @Param("season") Integer season, Pageable pageable);

    /**
     * Visible episode ids of a feed, optionally filtered by season, in the same canonical order as
     * {@link #findVisible} (§6.2). An id projection for {@code FeedAccess.episodesIn} — avoids hydrating
     * full entities when only ids are needed.
     */
    @Query("""
            select e.id from EpisodeRef e
            where e.feedId = :feedId and e.status <> 'WITHDRAWN'
              and (:season is null or e.season = :season)
              and e.feedId in (select f.id from Feed f where f.enabled = true)
            order by e.season asc nulls last, e.episodeNo asc nulls last, e.firstSeenAt desc
            """)
    List<UUID> findVisibleIds(@Param("feedId") UUID feedId, @Param("season") Integer season);

    /**
     * The unified site-scope episode feed (§6.1): visible episodes across all feeds (or one, when
     * {@code feedId} is given), optionally season-filtered, as a page of ref ids in display order. Upcoming
     * (PLANNED) episodes surface first; the rest sort by the feed snapshot's {@code publishedAt}
     * (newest/oldest per {@code newest}), cast to numeric so epoch-second strings order chronologically.
     * A LEFT JOIN keeps PLANNED refs, which have no {@code episode_display} row. Returns ids (like search)
     * so the service can batch-resolve snapshots and preserve order.
     */
    @Query(value = """
            select er.id
            from episode_ref er
            left join episode_display ed on ed.episode_ref_id = er.id
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and (cast(:feedId as uuid) is null or er.feed_id = cast(:feedId as uuid))
              and (cast(:season as int) is null or er.season = :season)
              and (cast(:tag as text) is null
                   or exists (select 1 from episode_tag et where et.episode_ref_id = er.id and et.tag = :tag))
            order by
              case when er.status = 'PLANNED' then 0 else 1 end,
              case when :newest then (ed.snapshot->>'publishedAt')::numeric end desc nulls last,
              case when not :newest then (ed.snapshot->>'publishedAt')::numeric end asc nulls last,
              er.first_seen_at desc
            """,
            countQuery = """
            select count(*)
            from episode_ref er
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and (cast(:feedId as uuid) is null or er.feed_id = cast(:feedId as uuid))
              and (cast(:season as int) is null or er.season = :season)
              and (cast(:tag as text) is null
                   or exists (select 1 from episode_tag et where et.episode_ref_id = er.id and et.tag = :tag))
            """,
            nativeQuery = true)
    Page<UUID> findSiteVisibleIds(
            @Param("feedId") UUID feedId,
            @Param("season") Integer season,
            @Param("tag") String tag,
            @Param("newest") boolean newest,
            Pageable pageable);
}
