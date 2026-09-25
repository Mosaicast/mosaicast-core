// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the presentation layer ({@link EpisodeDisplay}) plus episode full-text search. */
public interface EpisodeDisplayRepository extends JpaRepository<EpisodeDisplay, UUID> {

    /**
     * Full-text search over the display snapshots (ARCHITECTURE §E1), backed by the GIN index from V2.
     * Returns matching {@link EpisodeRef} ids ranked by relevance, excluding WITHDRAWN episodes. The
     * {@code simple} config keeps it language-agnostic (feed content stays in its original language).
     *
     * <p>The ref id breaks ties in the rank. It is not cosmetic: {@code ts_rank} produces long runs of
     * identical scores over short show notes, and an {@code ORDER BY} that does not totally order the rows
     * lets Postgres return them in any order it likes per {@code LIMIT}/{@code OFFSET} — so the same episode
     * shows up on page one and page two while another is never shown at all.
     */
    @Query(value = """
            select ed.episode_ref_id
            from episode_display ed
            join episode_ref er on er.id = ed.episode_ref_id
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and to_tsvector('simple',
                    coalesce(ed.snapshot ->> 'title', '') || ' ' || coalesce(ed.snapshot ->> 'description', ''))
                  @@ plainto_tsquery('simple', :q)
            order by ts_rank(to_tsvector('simple',
                    coalesce(ed.snapshot ->> 'title', '') || ' ' || coalesce(ed.snapshot ->> 'description', '')),
                    plainto_tsquery('simple', :q)) desc,
                ed.episode_ref_id
            """,
            countQuery = """
            select count(*)
            from episode_display ed
            join episode_ref er on er.id = ed.episode_ref_id
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and to_tsvector('simple',
                    coalesce(ed.snapshot ->> 'title', '') || ' ' || coalesce(ed.snapshot ->> 'description', ''))
                  @@ plainto_tsquery('simple', :q)
            """,
            nativeQuery = true)
    Page<UUID> search(@Param("q") String q, Pageable pageable);
}
