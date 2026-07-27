// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.log.AppLogProperties;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caps how much one plugin may write to the operational log (ARCHITECTURE §7.8, §13). A plugin component
 * caught in a render loop would otherwise fill the table and drown every other entry — the log has to stay
 * readable precisely when something is misbehaving.
 *
 * <p>A fixed window per plugin, not a sliding one: an operator cares that a plugin is spamming, not about
 * exact fairness at the window edge. The first rejection in a window is reported once, so throttling is
 * visible in the log rather than silent.
 */
@Component
public class PluginLogRateLimiter {

    private static final long WINDOW_SECONDS = 60;

    private final AppLogProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PluginLogRateLimiter(AppLogProperties properties) {
        this.properties = properties;
    }

    /** Outcome of asking for permission to write one entry. */
    public enum Decision {
        /** Within budget. */
        ALLOWED,
        /** Over budget, and this is the first rejection of the window — worth one notice. */
        THROTTLED_FIRST,
        /** Over budget, already reported this window — drop silently. */
        THROTTLED
    }

    public Decision check(String pluginId) {
        int limit = Math.max(1, properties.pluginRatePerMinute());
        Window window = windows.compute(pluginId, (id, current) -> {
            Instant now = Instant.now();
            if (current == null || now.isAfter(current.startedAt.plusSeconds(WINDOW_SECONDS))) {
                return new Window(now, 1, false);
            }
            return new Window(current.startedAt, current.count + 1, current.reported);
        });
        if (window.count <= limit) {
            return Decision.ALLOWED;
        }
        if (!window.reported) {
            windows.put(pluginId, new Window(window.startedAt, window.count, true));
            return Decision.THROTTLED_FIRST;
        }
        return Decision.THROTTLED;
    }

    private record Window(Instant startedAt, int count, boolean reported) {
    }
}
