// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counters' storage (ARCHITECTURE §19). Plain SQL rather than JPA: every write is an upsert that adds
 * to or replaces a number, which is one statement here and a read-modify-write race through an entity.
 */
@Repository
public class MetricsStore {

    /** Hour rows older than this are folded into day rows; the edge never resends hours this old (§18.5). */
    public static final int HOURLY_DAYS = 30;

    private final JdbcTemplate jdbc;

    public MetricsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds core's counts. Additive, so several core instances simply add up. */
    @Transactional
    public void add(Map<MetricKey, Long> deltas) {
        jdbc.batchUpdate("""
                insert into metric_counts (bucket, span, source, kind, subject, milestone, count)
                values (?, 'hour', ?, ?, ?, ?, ?)
                on conflict (bucket, span, source, kind, subject, milestone)
                do update set count = metric_counts.count + excluded.count""",
                deltas.entrySet(), deltas.size(), (ps, e) -> {
                    MetricKey k = e.getKey();
                    ps.setTimestamp(1, Timestamp.from(k.hour()));
                    ps.setString(2, k.source());
                    ps.setString(3, k.kind());
                    ps.setString(4, k.subject());
                    ps.setInt(5, k.milestone());
                    ps.setLong(6, e.getValue());
                });
    }

    /** Stores one hour the edge reported. SET semantics: a resend replaces with the same numbers (§18.5). */
    @Transactional
    public void setEdgeHour(Instant hour, Map<String, Long> viewsByKind) {
        jdbc.batchUpdate("""
                insert into metric_counts (bucket, span, source, kind, subject, milestone, count)
                values (?, 'hour', 'edge', 'view', ?, 0, ?)
                on conflict (bucket, span, source, kind, subject, milestone)
                do update set count = excluded.count""",
                viewsByKind.entrySet(), viewsByKind.size(), (ps, e) -> {
                    ps.setTimestamp(1, Timestamp.from(hour));
                    ps.setString(2, "edge/" + e.getKey());
                    ps.setLong(3, e.getValue());
                });
    }

    /** Raises a gauge's peak for a scope if {@code value} is higher. */
    public void raisePeak(String name, String scope, long value, Instant now) {
        jdbc.update("""
                insert into metric_gauge (name, scope, max, updated_at) values (?, ?, ?, ?)
                on conflict (name, scope) do update
                set max = greatest(metric_gauge.max, excluded.max),
                    updated_at = case when excluded.max > metric_gauge.max then excluded.updated_at
                                      else metric_gauge.updated_at end""",
                name, scope, value, Timestamp.from(now));
    }

    /** A gauge's peak for a scope, or 0. */
    public long peak(String name, String scope) {
        List<Long> rows = jdbc.queryForList("select max from metric_gauge where name = ? and scope = ?",
                Long.class, name, scope);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    /**
     * Folds hour rows older than {@link #HOURLY_DAYS} into day rows (adding to any already there) and
     * deletes them. Idempotent: what was folded is gone.
     *
     * @return the number of hour rows folded
     */
    @Transactional
    public int foldOldHours(Instant now) {
        Instant cutoff = now.truncatedTo(ChronoUnit.DAYS).minus(HOURLY_DAYS, ChronoUnit.DAYS);
        Timestamp before = Timestamp.from(cutoff);
        jdbc.update("""
                insert into metric_counts (bucket, span, source, kind, subject, milestone, count)
                select date_trunc('day', bucket at time zone 'UTC') at time zone 'UTC', 'day', source, kind,
                       subject, milestone, sum(count)
                from metric_counts where span = 'hour' and bucket < ?
                group by 1, source, kind, subject, milestone
                on conflict (bucket, span, source, kind, subject, milestone)
                do update set count = metric_counts.count + excluded.count""", before);
        return jdbc.update("delete from metric_counts where span = 'hour' and bucket < ?", before);
    }

    /** One row of a time series. */
    public record SeriesPoint(Instant bucket, String source, String kind, long count) {
    }

    /**
     * Totals per bucket, source and kind in a range, at the requested granularity; hourly data is summed
     * into days for {@code day}, and day rows (older than the hourly window) answer either granularity.
     */
    public List<SeriesPoint> series(Instant from, Instant to, boolean daily) {
        String bucket = daily
                ? "date_trunc('day', bucket at time zone 'UTC') at time zone 'UTC'"
                : "bucket";
        return jdbc.query("""
                select %s as b, source, kind, sum(count) as n from metric_counts
                where bucket >= ? and bucket < ? and kind <> 'progress'
                group by b, source, kind order by b""".formatted(bucket),
                (rs, i) -> new SeriesPoint(rs.getTimestamp("b").toInstant(), rs.getString("source"),
                        rs.getString("kind"), rs.getLong("n")),
                Timestamp.from(from), Timestamp.from(to));
    }

    /** A subject's total for one kind (and milestone) in a range. */
    public record SubjectTotal(String subject, String kind, int milestone, long count) {
    }

    /** Totals per subject, kind and milestone in a range, largest first. */
    public List<SubjectTotal> totals(Instant from, Instant to, String source, int limit) {
        return jdbc.query("""
                select subject, kind, milestone, sum(count) as n from metric_counts
                where bucket >= ? and bucket < ? and source = ?
                group by subject, kind, milestone order by n desc limit ?""",
                (rs, i) -> new SubjectTotal(rs.getString("subject"), rs.getString("kind"),
                        rs.getInt("milestone"), rs.getLong("n")),
                Timestamp.from(from), Timestamp.from(to), source, limit);
    }
}
