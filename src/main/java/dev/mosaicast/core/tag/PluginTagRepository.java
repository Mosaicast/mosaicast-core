// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.tag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for a plugin's own {@link PluginTag} assignments. Every query is keyed by {@code pluginId},
 * which the host takes from the caller's identity — there is no way to ask about another plugin's subjects.
 */
public interface PluginTagRepository extends JpaRepository<PluginTag, PluginTag.Key> {

    /** The plugin's subject keys carrying a tag. */
    @Query("""
            select p.id.subjectKey from PluginTag p
            where p.id.pluginId = :pluginId and p.id.tag = :tag
            order by p.id.subjectKey
            """)
    List<String> subjectsWith(@Param("pluginId") String pluginId, @Param("tag") String tag);

    /** The tags on one of the plugin's subjects. */
    @Query("""
            select p.id.tag from PluginTag p
            where p.id.pluginId = :pluginId and p.id.subjectKey = :subjectKey
            order by p.id.tag
            """)
    List<String> tagsOnSubject(@Param("pluginId") String pluginId, @Param("subjectKey") String subjectKey);

    /**
     * Drops everything a plugin ever tagged.
     *
     * <p>Called when an operator purges a plugin's data: assignments are the plugin's, unlike the vocabulary
     * entries they point at, which are the site's and stay.
     */
    @Modifying
    @Query("delete from PluginTag p where p.id.pluginId = :pluginId")
    int deleteByPluginId(@Param("pluginId") String pluginId);
}
