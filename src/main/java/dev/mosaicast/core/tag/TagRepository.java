// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the shared {@link Tag} vocabulary and the counts the plugin surface reports. */
public interface TagRepository extends JpaRepository<Tag, String> {

    /**
     * The whole vocabulary with its reach, most used first — what an editor offers instead of a free-text
     * box.
     *
     * <p>{@code episodes} counts <em>visible</em> episodes site-wide, whoever tagged them, because a tag's
     * reach is a property of the site rather than of the plugin asking. {@code subjects} counts only the
     * calling plugin's own: a plugin must not learn the size of a store it cannot read.
     *
     * <p>Both counts are {@code count(DISTINCT …)} over the assignment tables — an episode carrying a tag
     * from both the feed and a plugin is one episode, not two.
     */
    @Query(value = """
            select t.tag as tag,
                   t.label as label,
                   (select count(distinct et.episode_ref_id)
                      from episode_tag et
                      join episode_ref er on er.id = et.episode_ref_id
                     where et.tag = t.tag
                       and er.status <> 'WITHDRAWN'
                       and er.feed_id in (select f.id from feed f where f.enabled = true)) as episodes,
                   (select count(distinct pt.subject_key)
                      from plugin_tag pt
                     where pt.tag = t.tag and pt.plugin_id = :pluginId) as subjects
            from tag t
            order by episodes desc, t.tag asc
            """,
            nativeQuery = true)
    List<TagCount> vocabularyFor(@Param("pluginId") String pluginId);

    /**
     * Tags that appear alongside {@code tag} on the same visible episodes, best first.
     *
     * <p>Co-occurrence, which is the same topical signal {@code DefaultRelatedProvider} reads — it answers
     * "what else on this site is about this", the question a private per-plugin tag column can never answer.
     * The ranking is deliberately not part of the plugin contract, so this may change shape later.
     */
    @Query(value = """
            select other.tag as tag,
                   t.label as label,
                   count(distinct other.episode_ref_id) as episodes,
                   (select count(distinct pt.subject_key)
                      from plugin_tag pt
                     where pt.tag = other.tag and pt.plugin_id = :pluginId) as subjects
            from episode_tag mine
            join episode_tag other on other.episode_ref_id = mine.episode_ref_id and other.tag <> mine.tag
            join episode_ref er on er.id = other.episode_ref_id
            join tag t on t.tag = other.tag
            where mine.tag = :tag
              and er.status <> 'WITHDRAWN'
              and er.feed_id in (select f.id from feed f where f.enabled = true)
            group by other.tag, t.label
            order by episodes desc, other.tag asc
            limit :limit
            """,
            nativeQuery = true)
    List<TagCount> similarTo(@Param("tag") String tag, @Param("pluginId") String pluginId,
                             @Param("limit") int limit);

    /** A tag with its reach, as the vocabulary queries project it. */
    interface TagCount {
        String getTag();

        String getLabel();

        long getEpisodes();

        long getSubjects();
    }
}
