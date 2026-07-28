// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import dev.mosaicast.core.feed.FeedService;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginRegistration;
import dev.mosaicast.plugin.api.PlatformApi;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Writes one summary of what this instance actually is once it is serving (ARCHITECTURE §13).
 *
 * <p>"Which version, which profile, how many plugins, how many feeds" is the first question of every
 * incident and the last thing anyone can reconstruct afterwards. It runs on {@code ApplicationReadyEvent},
 * after the plugin loader (an {@code ApplicationRunner}), so the counts are final rather than in-flight.
 */
@Component
@Order(Integer.MAX_VALUE)
public class StartupSummary {

    private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

    private final PluginLoaderService plugins;
    private final FeedService feeds;
    private final AppLogProperties logProperties;
    private final Environment environment;
    private final String version;

    public StartupSummary(PluginLoaderService plugins, FeedService feeds, AppLogProperties logProperties,
                          Environment environment, @Value("${mosaicast.version:dev}") String version) {
        this.plugins = plugins;
        this.feeds = feeds;
        this.logProperties = logProperties;
        this.environment = environment;
        this.version = version;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSummary() {
        String profiles = environment.getActiveProfiles().length == 0
                ? "default" : String.join(",", environment.getActiveProfiles());
        log.info("Mosaicast {} ready — profile {}, Java {}, plugin contract {}, capturing logs at {} and above",
                version, profiles, Runtime.version().feature(), PlatformApi.VERSION,
                logProperties.threshold());

        Map<String, Long> byStatus = plugins.all().stream()
                .collect(Collectors.groupingBy(r -> r.status().name(), Collectors.counting()));
        if (byStatus.isEmpty()) {
            log.info("No plugins installed");
        } else {
            log.info("Plugins: {} loaded, {} disabled, {} rejected",
                    byStatus.getOrDefault("LOADED", 0L), byStatus.getOrDefault("DISABLED", 0L),
                    byStatus.getOrDefault("REJECTED", 0L));
            // Name the ones that are not running — the summary should answer "what is missing?" on its own.
            plugins.all().stream()
                    .filter(r -> r.status() != PluginRegistration.Status.LOADED)
                    .forEach(r -> log.info("  plugin '{}' is {}{}", r.id(), r.status().name().toLowerCase(),
                            r.reason() == null ? "" : ": " + r.reason()));
        }

        long enabled = feeds.list().stream().filter(f -> f.enabled()).count();
        log.info("Feeds: {} enabled, {} total", enabled, feeds.list().size());
    }
}
