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

    /**
     * Clears the tags <strong>the feed owns</strong> on an episode, before re-writing them from a fresh poll.
     *
     * <p>Narrowed to one source on purpose (§6.1). The unqualified delete this replaced took every tag on the
     * episode with it, so a podcaster's manual tag and a plugin's assignment survived exactly until the next
     * poll — which is minutes, and looked like the tag had never been saved.
     */
    @Modifying
    @Query("delete from EpisodeTag t where t.id.episodeRefId = :refId and t.id.source = :source")
    void deleteByEpisodeRefIdAndSource(@Param("refId") UUID refId, @Param("source") String source);

    /**
     * Distinct tags across visible episodes with their display labels, optionally scoped to one feed (§6.1) —
     * the options for the shell's tag filter, alphabetical by key. Excludes WITHDRAWN episodes.
     *
     * <p>The label comes from the vocabulary rather than from the assignment: the row carries the canonical
     * key, and lower-casing every tag on screen because the host normalises internally would be a visible
     * regression for a purely internal rule.
     */
    @Query(value = """
            select distinct t.tag as tag, v.label as label
            from episode_tag t
            join episode_ref er on er.id = t.episode_ref_id
            join tag v on v.tag = t.tag
            where er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
              and (cast(:feedId as uuid) is null or er.feed_id = cast(:feedId as uuid))
            order by t.tag
            """,
            nativeQuery = true)
    List<TagOptionRow> distinctTags(@Param("feedId") UUID feedId);

    /** The public slugs of visible episodes carrying a tag, whoever tagged them (§6.1). */
    @Query(value = """
            select er.slug
            from episode_tag t
            join episode_ref er on er.id = t.episode_ref_id
            where t.tag = :tag
              and er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            group by er.slug
            order by max(er.first_seen_at) desc
            """,
            nativeQuery = true)
    List<String> visibleSlugsWithTag(@Param("tag") String tag);

    /** The tags on one visible episode, whatever put them there. */
    @Query(value = """
            select distinct t.tag
            from episode_tag t
            join episode_ref er on er.id = t.episode_ref_id
            where er.slug = :slug
              and er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            order by t.tag
            """,
            nativeQuery = true)
    List<String> visibleTagsOnSlug(@Param("slug") String slug);

    /** Removes one writer's assignment, leaving any other writer's row for the same tag in place. */
    @Modifying
    @Query("""
            delete from EpisodeTag t
            where t.id.episodeRefId = :refId and t.id.tag = :tag and t.id.source = :source
            """)
    int deleteAssignment(@Param("refId") UUID refId, @Param("tag") String tag,
                         @Param("source") String source);

    /** Drops every episode assignment a plugin ever made — the episode half of purging a plugin's data. */
    @Modifying
    @Query("delete from EpisodeTag t where t.id.source = :source")
    int deleteBySource(@Param("source") String source);

    /** A tag with the label the vocabulary keeps for it, as the filter-options query projects it. */
    interface TagOptionRow {
        String getTag();

        String getLabel();
    }

    /**
     * An episode's own tags — one side of the shared-tag signal in {@link DefaultRelatedProvider}.
     *
     * <p>Distinct since {@code source} joined the key: a tag the feed and a plugin both put on an episode is
     * one topic, and counting it twice would weight the recommendation by how many writers agreed rather
     * than by how much two episodes have in common.
     */
    @Query("select distinct t.id.tag from EpisodeTag t where t.id.episodeRefId = :refId")
    List<String> findTags(@Param("refId") UUID refId);

    /**
     * Episode ids carrying any of the given tags, excluding one episode — the other side of that signal.
     *
     * <p>Pushed into the database rather than loading every tag row and intersecting in memory: the index on
     * {@code tag} makes this the narrow query it looks like, and the alternative grows with the catalogue
     * instead of with the answer. Repeated ids are the point — an episode sharing three tags appears three
     * times, and the caller counts them.
     *
     * <p>Grouped by {@code (episode, tag)} rather than returned raw, for the reason {@link #findTags} is
     * distinct: one row per shared <em>topic</em>, not one per writer who agreed about it.
     */
    @Query("""
            select t.id.episodeRefId from EpisodeTag t
            where t.id.tag in :tags and t.id.episodeRefId <> :excludeRefId
            group by t.id.episodeRefId, t.id.tag
            """)
    List<UUID> findRefIdsByTags(@Param("tags") List<String> tags, @Param("excludeRefId") UUID excludeRefId);
}
