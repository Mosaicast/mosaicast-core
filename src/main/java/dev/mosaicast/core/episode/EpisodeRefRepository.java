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

    /** A publicly visible ref by its public slug — the slug counterpart of {@link #findVisibleById}. */
    @Query("""
            select e from EpisodeRef e
            where e.slug = :slug and e.status <> 'WITHDRAWN'
              and e.feedId in (select f.id from Feed f where f.enabled = true)
            """)
    Optional<EpisodeRef> findVisibleBySlug(@Param("slug") String slug);

    /**
     * Publicly visible refs for a batch of slugs — the plugin {@code ctx.feeds} read, and the reason it is a
     * batch: a plugin drawing twenty cards should cost one query, not twenty.
     */
    @Query("""
            select e from EpisodeRef e
            where e.slug in :slugs and e.status <> 'WITHDRAWN'
              and e.feedId in (select f.id from Feed f where f.enabled = true)
            """)
    List<EpisodeRef> findVisibleBySlugIn(@Param("slugs") List<String> slugs);

    /** Whether a slug is already taken (uniqueness guard when minting a new slug). */
    boolean existsBySlug(String slug);

    /**
     * A ref by slug regardless of visibility — the admin counterpart of {@link #findVisibleBySlug}.
     *
     * <p>Curating pins (§6.3) has to reach a withdrawn or disabled-feed episode, if only to unpin it.
     * Public reads keep using the visible variant; this one must never back a public endpoint.
     */
    Optional<EpisodeRef> findBySlug(String slug);

    /** Refs created before slugs existed, for the boot-time backfill. */
    List<EpisodeRef> findBySlugIsNull();

    /** How many refs still have no slug — the backfill's post-condition check, so it cannot report a false success. */
    long countBySlugIsNull();

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
     *
     * <p>This is deliberately <em>not</em> the site list's order, and the difference is the product: a feed
     * page is browsed a season at a time, oldest first within one; the site list is a reverse-chronological
     * river across every feed. What was wrong is only the tie-break. A feed whose host leaves
     * {@code itunes:season} and {@code itunes:episode} unset — Acast does — has nothing to sort by but the
     * tie-break, so the whole list fell back to {@code first_seen_at}: the order the host happened to
     * *ingest* them in, presented to a listener as if it were the order they were released in (core#184).
     * It is the snapshot's {@code publishedAt} now, with {@code first_seen_at} behind it for an episode that
     * carries no date at all. Native, and joined the way the site list is, because {@code publishedAt} lives
     * in the snapshot JSONB and there is no column to order by.
     */
    @Query(value = """
            select er.*
            from episode_ref er
            left join episode_display ed on ed.episode_ref_id = er.id
            where er.feed_id = cast(:feedId as uuid) and er.status <> 'WITHDRAWN'
              and (cast(:season as int) is null or er.season = :season)
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            order by
              er.season asc nulls last,
              er.episode_no asc nulls last,
              (ed.snapshot->>'publishedAt')::numeric asc nulls first,
              er.first_seen_at desc,
              er.id asc
            """,
            countQuery = """
            select count(*)
            from episode_ref er
            where er.feed_id = cast(:feedId as uuid) and er.status <> 'WITHDRAWN'
              and (cast(:season as int) is null or er.season = :season)
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            """,
            nativeQuery = true)
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
     *
     * <p>An episode with no {@code publishedAt} (e.g. an "episode 0" trailer that ships without a
     * {@code pubDate}) is treated as the <em>earliest</em> point in the series rather than being dumped at
     * the end: {@code nulls last} in newest order, {@code nulls first} in oldest order. Ties (equal or
     * absent dates) then fall back to season/episode number, so a dateless S1E0 sits before S1E1.
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
              case when not :newest then (ed.snapshot->>'publishedAt')::numeric end asc nulls first,
              er.season asc nulls last,
              er.episode_no asc nulls last,
              er.first_seen_at desc,
              er.id
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

    /**
     * A feed's navigation sequence (§6.2): its <em>released</em> episodes, oldest→newest by the feed
     * snapshot's {@code publishedAt}. Same release order as the browsable feed ({@link #findSiteVisibleIds}
     * with {@code newest=false}) but without {@code PLANNED} refs: upcoming episodes lead the listing yet
     * have no audio, so they must never be a prev/next neighbour — the player auto-advances into {@code next}
     * and would land on an unplayable episode. An episode with no {@code publishedAt} counts as the series
     * start ({@code nulls first}); ties fall back to season/episode number.
     */
    @Query(value = """
            select er.id
            from episode_ref er
            left join episode_display ed on ed.episode_ref_id = er.id
            where er.status not in ('WITHDRAWN', 'PLANNED')
              and er.feed_id = :feedId
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            order by
              (ed.snapshot->>'publishedAt')::numeric asc nulls first,
              er.season asc nulls last,
              er.episode_no asc nulls last,
              er.first_seen_at desc
            """,
            nativeQuery = true)
    List<UUID> findNavSequenceIds(@Param("feedId") UUID feedId);
}
