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

    /**
     * Counts one log line against the plugin's budget and says what to do with it.
     *
     * <p>The whole decision happens inside a single {@code compute}, including marking the window as
     * reported. It used to {@code compute} and then {@code put} a modified copy, which is two atomic
     * operations and therefore not one: two threads could both see {@code reported == false} and both emit
     * the "being throttled" notice, and — worse — a thread sitting between the two calls while the window
     * rolled over would write its stale {@code startedAt} and inflated {@code count} back over the fresh
     * window a racing thread had just started, so the new minute was born already exhausted and the plugin
     * stayed silenced for an extra one.
     *
     * <p>The mapping function must stay free of side effects and be safe to re-run, which is why the outcome
     * is carried out through {@code decision} on the window itself rather than assigned to a local.
     */
    public Decision check(String pluginId) {
        int limit = Math.max(1, properties.pluginRatePerMinute());
        Window window = windows.compute(pluginId, (id, current) -> {
            Instant now = Instant.now();
            if (current == null || now.isAfter(current.startedAt.plusSeconds(WINDOW_SECONDS))) {
                return new Window(now, 1, false, Decision.ALLOWED);
            }
            int count = current.count + 1;
            if (count <= limit) {
                return new Window(current.startedAt, count, current.reported, Decision.ALLOWED);
            }
            if (!current.reported) {
                return new Window(current.startedAt, count, true, Decision.THROTTLED_FIRST);
            }
            return new Window(current.startedAt, count, true, Decision.THROTTLED);
        });
        return window.decision;
    }

    private record Window(Instant startedAt, int count, boolean reported, Decision decision) {
    }
}
