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
    /**
     * Which of the given users this plugin already holds {@code USER}-scope data for (§17.1).
     *
     * <p>The eligibility rule behind {@code ctx.notify}, and the reason it needed no new concept: a plugin
     * may write into another user's experience only where that user already has rows in it — bingo reaches
     * its participants because participants have rows, and nothing reaches a user who never touched the
     * plugin. Read against the same partitions {@code queryAcrossUsers} spans.
     *
     * <p>The scope type is <strong>passed in</strong> rather than written here, because spelling it inline
     * is exactly what broke this: the literal read {@code 'USER'} while the column stores {@code user}, so
     * the query matched nothing and every plugin send reached nobody, silently.
     *
     * @param pluginId  the sender
     * @param scopeType {@link DataScope#USER_TYPE}
     * @param scopeIds  the candidate user ids, as scope-id strings
     * @return the subset that has data, as scope-id strings
     */
    @Query("""
            select distinct d.id.scopeId from PluginData d
            where d.id.pluginId = :pluginId
              and d.id.scopeType = :scopeType
              and d.id.scopeId in :scopeIds
            """)
    java.util.Set<String> userScopesHeldBy(@Param("pluginId") String pluginId,
                                           @Param("scopeType") String scopeType,
                                           @Param("scopeIds") java.util.Collection<String> scopeIds);

    /** Everything one plugin stored, dropped when the plugin's data goes (§17.2). */
    @Query("select count(d) from PluginData d where d.id.pluginId = :pluginId")
    long countForPlugin(@Param("pluginId") String pluginId);

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
     * Every document of one plugin in one scope <em>type</em>, across all scope ids — the aggregate behind
     * {@code DocStore.queryAcrossUsers} (§7.4).
     *
     * <p>Deliberately narrow in what it is used for: this is the only query in the doc store that spans owner
     * partitions, so it is reachable only from a plugin's own backend and never from the HTTP surface. Ordered
     * by scope id then key so a rollup is stable between runs.
     */
    @Query("""
            select d from PluginData d
            where d.id.pluginId = :pluginId
              and d.id.scopeType = :scopeType
              and d.id.key like concat(:prefix, '%')
            order by d.id.scopeId asc, d.id.key asc
            """)
    List<PluginData> findInScopeType(
            @Param("pluginId") String pluginId,
            @Param("scopeType") String scopeType,
            @Param("prefix") String prefix);

    /**
     * Deletes every document of one plugin, across all scopes — the primitive behind the admin's
     * "purge plugin data" action (ARCHITECTURE §7.8). Returns the number of documents removed.
     */
    @Modifying
    @Query("delete from PluginData d where d.id.pluginId = :pluginId")
    int deleteByPluginId(@Param("pluginId") String pluginId);

    /**
     * Deletes every plugin's documents in one <em>user's</em> partition, across all plugins (§12).
     *
     * <p>The one deletion that crosses plugin boundaries, and the reason it may: the {@code USER} scope is
     * host-owned — the id in the key is the host's, substituted server-side — so a user's partition is
     * core's to drop when that user's account goes. What core cannot touch is anything a plugin put in its
     * own schema columns or files, which is what {@code UserDataHandler} exists for.
     */
    @Modifying
    @Query("""
            delete from PluginData d
            where d.id.scopeType = :scopeType and d.id.scopeId = :userId
            """)
    int deleteUserScope(@Param("scopeType") String scopeType, @Param("userId") String userId);

    /** Whether a plugin has ever stored anything — used to decide whether a broken plugin owes an erasure. */
    boolean existsByIdPluginId(String pluginId);

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
