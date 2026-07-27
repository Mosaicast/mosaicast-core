// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import dev.mosaicast.core.web.PagedResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin log viewer's read API (ARCHITECTURE §13). ADMIN-only through the existing {@code /api/admin/**}
 * rule. Filters are all optional and combine; an omitted filter means "no restriction".
 */
@RestController
public class AdminLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AppLogService logs;

    public AdminLogController(AppLogService logs) {
        this.logs = logs;
    }

    /**
     * @param level minimum severity — {@code WARN} returns WARN and ERROR; omitted returns every level
     */
    @GetMapping("/api/admin/logs")
    public PagedResponse<AppLogView> list(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String subsystem,
            @RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        return PagedResponse.of(
                logs.search(normalizeLevel(level), subsystem, pluginId, parseSince(since), q, pageable),
                AppLogView::of);
    }

    /** Subsystems and plugin ids that actually occur, so the filter offers no dead options. */
    @GetMapping("/api/admin/logs/facets")
    public AppLogService.Facets facets() {
        return logs.facets();
    }

    /** An unparsable level filters nothing rather than 400ing a viewer that sent a stale value. */
    private static String normalizeLevel(String level) {
        return AppLogLevel.parse(level).map(Enum::name).orElse(null);
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("since must be an ISO-8601 instant, e.g. 2026-07-27T10:15:30Z");
        }
    }
}
