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
              and (cast(:feedId as uuid) is null or er.feed_id = cast(:feedId as uuid))
            order by t.tag
            """,
            nativeQuery = true)
    List<String> distinctTags(@Param("feedId") UUID feedId);
}
