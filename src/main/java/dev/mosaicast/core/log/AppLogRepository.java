// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link AppLogEntry}: the viewer's filtered page, its facets, and retention. */
public interface AppLogRepository extends JpaRepository<AppLogEntry, Long> {

    /**
     * One page of the viewer, newest first. {@code levels} is the set to include — the caller expands a
     * chosen minimum severity into "that level and above", because a viewer filtered to WARN that hid ERRORs
     * would be actively misleading. The remaining filters are optional, expressed as a <em>sentinel</em>
     * rather than a null: an empty string (or {@link java.time.Instant#EPOCH}) means "no restriction". Postgres
     * cannot infer the type of a null bind parameter — a null text filter here reaches the driver as
     * {@code bytea} and the query fails with {@code function lower(bytea) does not exist} — and sentinels
     * avoid that without scattering casts through the JPQL. The id is the tie-breaker so entries written in
     * the same millisecond still page deterministically.
     */
    @Query("""
            select e from AppLogEntry e
            where (e.level in :levels)
              and (:subsystem = '' or e.subsystem = :subsystem)
              and (:pluginId = '' or e.pluginId = :pluginId)
              and (e.at >= :since)
              and (:text = '' or lower(e.message) like lower(concat('%', :text, '%')))
            order by e.at desc, e.id desc
            """)
    Page<AppLogEntry> search(
            @Param("levels") java.util.Collection<String> levels,
            @Param("subsystem") String subsystem,
            @Param("pluginId") String pluginId,
            @Param("since") Instant since,
            @Param("text") String text,
            Pageable pageable);

    /** Subsystems that actually occur, so the filter offers no dead options. */
    @Query("select distinct e.subsystem from AppLogEntry e order by e.subsystem")
    List<String> distinctSubsystems();

    /** Plugin ids that actually occur. */
    @Query("select distinct e.pluginId from AppLogEntry e where e.pluginId is not null order by e.pluginId")
    List<String> distinctPluginIds();

    /** Same, split by subsystem: {@code [subsystem, level, count]}. */
    @Query("""
            select e.subsystem, e.level, count(e) from AppLogEntry e
            where e.at >= :since and e.level in ('ERROR', 'WARN')
            group by e.subsystem, e.level
            order by e.subsystem
            """)
    List<Object[]> countBySubsystemSince(@Param("since") Instant since);

    /** Retention by age. */
    @Modifying
    @Query("delete from AppLogEntry e where e.at < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Retention by row count: keeps the newest {@code maxRows}.
     *
     * <p>The cut-off is the first row past the newest {@code maxRows} — it and everything older go — found by
     * walking the primary key backwards. It used to be {@code max(id) - maxRows}, justified by "ids are monotonic" — but that
     * arithmetic needs <em>dense</em> ids, and a {@code BIGSERIAL} is not: a failed insert or a cached
     * sequence block leaves gaps, and the writer does fail inserts under load. With gaps it deleted rows while
     * the table was still under its limit (core#201). Only {@code maxRows} index entries are read either way.
     */
    @Modifying
    @Query(value = """
            delete from app_log
            where id <= (select id from app_log order by id desc offset :maxRows limit 1)
            """, nativeQuery = true)
    int trimToMaxRows(@Param("maxRows") long maxRows);
}
