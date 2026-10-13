// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.siteaccess.EdgeHeartbeat;
import dev.mosaicast.core.siteaccess.SiteAccess;
import dev.mosaicast.core.siteaccess.SiteAccessService;
import dev.mosaicast.core.web.CodedBadRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Reach (ARCHITECTURE §19): the counts over a range, and core's counting switch. Reading is ADMIN
 * and PODCASTER, switching ADMIN only (SecurityConfig).
 */
@RestController
public class MetricsAdminController {

    /** The widest range one request may ask for. */
    static final Duration MAX_RANGE = Duration.ofDays(400);
    /** Ranges up to this long are answered per hour, longer ones per day. */
    static final Duration HOURLY_UP_TO = Duration.ofDays(3);

    private final MetricsStore store;
    private final SiteConfigService site;
    private final SiteAccessService access;
    private final EdgeHeartbeat heartbeat;
    private final Clock clock;

    public MetricsAdminController(MetricsStore store, SiteConfigService site, SiteAccessService access,
                                  EdgeHeartbeat heartbeat, Clock clock) {
        this.store = store;
        this.site = site;
        this.access = access;
        this.heartbeat = heartbeat;
        this.clock = clock;
    }

    /** The edge's numbers: what it reports now, and its peaks. */
    public record EdgeView(boolean counting, @Nullable Long waiting, boolean waitingCapped,
                           @Nullable Instant waitingAt, String page, long peakToday, long peakThisPolicy) {
    }

    /** Everything the Reach page shows for a range. */
    public record ReachView(boolean counting, Instant from, Instant to, String granularity,
                            List<MetricsStore.SeriesPoint> series, List<MetricsStore.SubjectTotal> top,
                            List<MetricsStore.SubjectTotal> edgeViews, EdgeView edge) {
    }

    /** Core's counting switch. */
    public record Settings(boolean counting) {
    }

    @GetMapping("/api/admin/metrics")
    public ReachView reach(@RequestParam(required = false) @Nullable Instant from,
                           @RequestParam(required = false) @Nullable Instant to) {
        Instant now = clock.instant();
        Instant end = to != null ? to : now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant start = from != null ? from : end.minus(7, ChronoUnit.DAYS);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new CodedBadRequest("metrics.range", "The range must be positive and at most 400 days.");
        }
        boolean daily = Duration.between(start, end).compareTo(HOURLY_UP_TO) > 0;
        return new ReachView(site.get().isCounting(), start, end, daily ? "day" : "hour",
                store.series(start, end, daily), store.totals(start, end, "core", 50),
                store.totals(start, end, "edge", 20), edge(now));
    }

    @PutMapping("/api/admin/metrics/settings")
    public Settings settings(@RequestBody Settings request) {
        return new Settings(site.updateCounting(request.counting()).isCounting());
    }

    private EdgeView edge(Instant now) {
        SiteAccess current = access.current();
        EdgeHeartbeat.Waiting waiting = heartbeat.waiting();
        String page = current.effectiveState(now).name().toLowerCase(Locale.ROOT);
        long today = store.peak("edge.waiting", "day:" + now.atZone(ZoneOffset.UTC).toLocalDate());
        long thisPolicy = store.peak("edge.waiting", "rev:" + current.getRevision());
        return new EdgeView(current.isEdgeCounting(), waiting == null ? null : waiting.count(),
                waiting != null && waiting.capped(), waiting == null ? null : waiting.at(), page, today,
                thisPolicy);
    }
}
