// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for episode {@link EpisodeTag}s: per-poll overwrite plus the shell's distinct-tag list. */
public interface EpisodeTagRepository extends JpaRepository<EpisodeTag, EpisodeTag.Key> {

    /** Clears an episode's tags before re-writing them from a fresh poll. */
    @Modifying
    @Query("delete from EpisodeTag t where t.id.episodeRefId = :refId")
    void deleteByEpisodeRefId(@Param("refId") UUID refId);

    /**
     * Distinct tags across visible episodes, optionally scoped to one feed (§6.1) — the options for the
     * shell's tag filter, alphabetical. Excludes WITHDRAWN episodes.
     */
    @Query(value = """
            select distinct t.tag
            from episode_tag t
            join episode_ref er on er.id = t.episode_ref_id
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and (cast(:feedId as uuid) is null or er.feed_id = cast(:feedId as uuid))
            order by t.tag
            """,
            nativeQuery = true)
    List<String> distinctTags(@Param("feedId") UUID feedId);

    /** An episode's own tags — one side of the shared-tag signal in {@link DefaultRelatedProvider}. */
    @Query("select t.id.tag from EpisodeTag t where t.id.episodeRefId = :refId")
    List<String> findTags(@Param("refId") UUID refId);

    /**
     * Episode ids carrying any of the given tags, excluding one episode — the other side of that signal.
     *
     * <p>Pushed into the database rather than loading every tag row and intersecting in memory: the index on
     * {@code tag} makes this the narrow query it looks like, and the alternative grows with the catalogue
     * instead of with the answer. Repeated ids are the point — an episode sharing three tags appears three
     * times, and the caller counts them.
     */
    @Query("""
            select t.id.episodeRefId from EpisodeTag t
            where t.id.tag in :tags and t.id.episodeRefId <> :excludeRefId
            """)
    List<UUID> findRefIdsByTags(@Param("tags") List<String> tags, @Param("excludeRefId") UUID excludeRefId);
}
