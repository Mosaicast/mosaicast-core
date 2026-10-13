// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.metrics;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.web.FixedWindowRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shell's counting beacon (ARCHITECTURE §19). Always 204 with no body, whatever happened to the events:
 * a caller learns nothing — not whether counting is on, not whether a slug exists, not whether it was
 * rate-limited. The client address and User-Agent are looked at for this request only and never kept.
 */
@RestController
public class MetricsController {

    static final int MAX_EVENTS = 10;
    /** Events per client per minute; beyond it they are dropped. In memory only, never persisted. */
    static final int EVENTS_PER_MINUTE = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Set<String> KINDS = Set.of("view", "play", "progress");
    private static final Set<Integer> MILESTONES = Set.of(25, 50, 75, 100);

    private final MetricsAggregator aggregator;
    private final SubjectValidator subjects;
    private final SiteConfigService site;
    private final Clock clock;
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter();

    public MetricsController(MetricsAggregator aggregator, SubjectValidator subjects, SiteConfigService site,
                             Clock clock) {
        this.aggregator = aggregator;
        this.subjects = subjects;
        this.site = site;
        this.clock = clock;
    }

    /** One counted thing. Nothing else travels: no identifier, no URL, no referrer. */
    public record Event(String kind, String subject, @Nullable Integer milestone) {
    }

    /** A batch from one page. */
    public record Events(List<Event> events) {
    }

    @PostMapping("/api/metrics/events")
    public ResponseEntity<Void> events(@RequestBody(required = false) @Nullable Events body,
                                       HttpServletRequest request) {
        if (body != null && body.events() != null && body.events().size() <= MAX_EVENTS
                && site.get().isCounting()
                && !CrawlerList.isCrawler(request.getHeader(HttpHeaders.USER_AGENT))
                && !"1".equals(request.getHeader("Sec-GPC"))) {
            for (Event event : body.events()) {
                if (accepted(event) && limiter.check(clientKey(request), EVENTS_PER_MINUTE, WINDOW,
                        clock.instant()).allowed()) {
                    int milestone = "progress".equals(event.kind()) ? event.milestone() : 0;
                    aggregator.count(event.kind(), event.subject(), milestone);
                }
            }
        }
        return ResponseEntity.noContent().build();
    }

    private boolean accepted(Event event) {
        if (event == null || event.kind() == null || !KINDS.contains(event.kind())) {
            return false;
        }
        boolean progress = "progress".equals(event.kind());
        if (progress != (event.milestone() != null) || (progress && !MILESTONES.contains(event.milestone()))) {
            return false;
        }
        return subjects.accepts(event.kind(), event.subject());
    }

    /** The client as the deployment resolves it (behind the edge: the visitor, §18.4). Never stored. */
    private static String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /** Forgets finished windows, so the map of clients never outlives a minute of traffic. */
    @Scheduled(fixedDelay = 60, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    void evict() {
        limiter.evictExpired(WINDOW, clock.instant());
    }
}
