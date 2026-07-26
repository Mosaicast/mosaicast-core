// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link PluginData}. Every query is scoped by {@code pluginId} first, so the doc store is
 * hard-scoped to the owning plugin (ARCHITECTURE §7.6).
 */
public interface PluginDataRepository extends JpaRepository<PluginData, PluginDataKey> {

    /**
     * Every document in a scope whose key starts with {@code prefix} (an empty prefix matches all), ordered
     * by key for a stable page. Bound to the plugin id, so it never crosses plugin boundaries.
     */
    @Query("""
            select d from PluginData d
            where d.id.pluginId = :pluginId
              and d.id.scopeType = :scopeType
              and d.id.scopeId = :scopeId
              and d.id.key like concat(:prefix, '%')
            order by d.id.key asc
            """)
    List<PluginData> findInScope(
            @Param("pluginId") String pluginId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("prefix") String prefix);

    /**
     * Deletes every document of one plugin, across all scopes — the primitive behind the admin's
     * "purge plugin data" action (ARCHITECTURE §7.8). Returns the number of documents removed.
     */
    @Modifying
    @Query("delete from PluginData d where d.id.pluginId = :pluginId")
    int deleteByPluginId(@Param("pluginId") String pluginId);

    /** Paginated variant of {@link #findInScope} for the host's HTTP list endpoint. */
    @Query("""
            select d from PluginData d
            where d.id.pluginId = :pluginId
              and d.id.scopeType = :scopeType
              and d.id.scopeId = :scopeId
              and d.id.key like concat(:prefix, '%')
            order by d.id.key asc
            """)
    Page<PluginData> pageInScope(
            @Param("pluginId") String pluginId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("prefix") String prefix,
            Pageable pageable);
}
