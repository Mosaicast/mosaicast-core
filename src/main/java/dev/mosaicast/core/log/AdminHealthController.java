// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import dev.mosaicast.core.feed.FeedService;
import dev.mosaicast.core.feed.FeedView;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's overview of how the site is doing (ARCHITECTURE §13): why each plugin is or is not running,
 * which feeds are failing <em>and with what error</em>, and how much has gone wrong lately. ADMIN-only via the
 * existing {@code /api/admin/**} rule.
 *
 * <p>Everything here already existed somewhere — plugin rejection reasons in the registry, {@code lastError}
 * on the feed row — but was scattered across pages or, in the feed case, fetched by the UI and never shown.
 * This is the one place that answers "is anything broken right now?".
 */
@RestController
public class AdminHealthController {

    private final PluginLoaderService plugins;
    private final FeedService feeds;
    private final AppLogService logs;
    private final AppLogProperties logProperties;
    private final String version;

    public AdminHealthController(PluginLoaderService plugins, FeedService feeds, AppLogService logs,
                                 AppLogProperties logProperties,
                                 @Value("${mosaicast.version:dev}") String version) {
        this.plugins = plugins;
        this.feeds = feeds;
        this.logs = logs;
        this.logProperties = logProperties;
        this.version = version;
    }

    @GetMapping("/api/admin/health")
    public HealthView health() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<PluginHealth> pluginHealth = plugins.all().stream()
                .map(r -> new PluginHealth(
                        r.id(),
                        r.manifest() == null ? null : r.manifest().name(),
                        r.status().name(),
                        plugins.isEnabled(r.id()),
                        r.reason()))
                .toList();
        List<FeedHealth> feedHealth = feeds.list().stream()
                .map(f -> new FeedHealth(f.id().toString(), f.title(), f.enabled(), f.lastFetchStatus(),
                        f.lastError(), f.consecutiveFailures(), f.lastFetchedAt()))
                .toList();
        return new HealthView(
                version,
                uptimeSeconds(),
                pluginHealth,
                feedHealth,
                logs.countsSince(since),
                since,
                logProperties.threshold().name());
    }

    private static long uptimeSeconds() {
        return Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds();
    }

    /**
     * @param counts       ERROR/WARN tallies per subsystem since {@code countsSince} — of <em>stored</em> rows
     * @param captureLevel the lowest level stored; a count below it is not zero but unknown, and the card
     *                     must say so rather than read "0 warnings" while warnings run on stdout (core#201)
     */
    public record HealthView(String version, long uptimeSeconds, List<PluginHealth> plugins,
                             List<FeedHealth> feeds, List<AppLogService.SubsystemCount> counts,
                             Instant countsSince, String captureLevel) {
    }

    /**
     * @param status  LOADED / DISABLED / REJECTED, as decided at boot
     * @param enabled the current admin switch — {@code LOADED} + {@code false} means switched off since boot
     * @param reason  why it was rejected, or {@code null}
     */
    public record PluginHealth(String id, String name, String status, boolean enabled, String reason) {
    }

    /** A feed's poll state, including the error text the admin feeds page never showed. */
    public record FeedHealth(String id, String title, boolean enabled, String lastFetchStatus,
                             String lastError, int consecutiveFailures, Instant lastFetchedAt) {
    }
}
