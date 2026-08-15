// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for podcaster-curated related episodes ({@link EpisodePin}, ARCHITECTURE §6.3). */
public interface EpisodePinRepository extends JpaRepository<EpisodePin, EpisodePin.Key> {

    /**
     * The pinned related ids for an episode, in display order, filtered to what a visitor may actually see.
     *
     * <p>The visibility predicate is the same one {@code EpisodeRefRepository.findVisibleById} applies, and
     * it is applied <em>here</em> rather than after loading: a pin to an episode that has since been
     * withdrawn, or whose feed an admin switched off, must not surface just because someone curated it once.
     * A pin records an intention, not a permission.
     */
    @Query("""
            select p.id.relatedRefId from EpisodePin p
            where p.id.episodeRefId = :refId
              and p.id.relatedRefId in (
                  select e.id from EpisodeRef e
                  where e.status = 'PUBLISHED'
                    and e.feedId in (select f.id from Feed f where f.enabled = true))
            order by p.position asc, p.id.relatedRefId asc
            """)
    List<UUID> findVisiblePinnedIds(@Param("refId") UUID refId);

    /** Every pin under an episode, visible or not — the admin view, which has to show what it can unpin. */
    List<EpisodePin> findByIdEpisodeRefIdOrderByPositionAsc(UUID episodeRefId);

    @Modifying
    @Query("delete from EpisodePin p where p.id.episodeRefId = :refId and p.id.relatedRefId = :relatedId")
    int unpin(@Param("refId") UUID refId, @Param("relatedId") UUID relatedId);

    /** The next free position under an episode, so a new pin lands at the end rather than colliding. */
    @Query("select coalesce(max(p.position), -1) + 1 from EpisodePin p where p.id.episodeRefId = :refId")
    int nextPosition(@Param("refId") UUID refId);
}
