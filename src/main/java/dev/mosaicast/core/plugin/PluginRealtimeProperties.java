// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The operator's side of the realtime surfaces (platformApi 0.20.0, ARCHITECTURE §7.9).
 *
 * <p>Where a manifest may ask for a number, the shape is the one notifications use (§17.1): {@code default-}
 * is what a plugin gets when it names none, {@code hard-} the ceiling no manifest can reach past. Everything
 * else is the host's alone — how many plugin calls may run at once, how long one may take, how a stream
 * survives a dropped connection — because a plugin that could set them could take the instance with it.
 *
 * @param defaultActionsPerUserPerMinute   calls one user may make to one plugin per minute, when unasked
 * @param hardActionsPerUserPerMinute      the most any plugin gets for that, whatever it asked
 * @param defaultActionsPerPluginPerMinute calls all users together may make to one plugin per minute
 * @param hardActionsPerPluginPerMinute    the ceiling for that
 * @param actionConcurrency                plugin calls (actions, authorizers, timers, join/leave) one plugin
 *                                         may run at once; also how many request threads it can hold
 * @param actionQueueWait                  how long a call waits for a free slot before a 503 action-busy
 * @param actionDeadline                   how long a caller waits for a handler before a 504 action-timeout
 * @param defaultSubscriptionsPerUser      concurrent subscriptions one user may hold to one plugin, unasked
 * @param hardSubscriptionsPerUser         the ceiling for that
 * @param subscribesPerUserPerMinute       subscribe requests one user may make per minute, all plugins
 * @param streamsPerUser                   open streams one user may hold — roughly, tabs
 * @param streamsPerInstance               open streams this instance holds before refusing more
 * @param heartbeat                        how often an idle stream is written to; must stay under the read
 *                                         timeout of any proxy in front (the documented nginx one is 60 s)
 * @param streamTimeout                    the longest one stream lives before the browser reopens it
 * @param resumeGrace                      how long a dropped stream's subscriptions wait for a reconnect
 * @param replayEvents                     events a stream keeps for a reconnect to replay
 * @param replayAge                        how old a kept event may be and still replay
 * @param sendQueue                        events waiting to be written to one stream before it is cut
 * @param maxPendingTimers                 {@code runAfter} tasks one plugin may have waiting
 */
@ConfigurationProperties(prefix = "mosaicast.plugin-realtime")
public record PluginRealtimeProperties(
        @DefaultValue("120") int defaultActionsPerUserPerMinute,
        @DefaultValue("600") int hardActionsPerUserPerMinute,
        @DefaultValue("3000") int defaultActionsPerPluginPerMinute,
        @DefaultValue("20000") int hardActionsPerPluginPerMinute,
        @DefaultValue("8") int actionConcurrency,
        @DefaultValue("1s") Duration actionQueueWait,
        @DefaultValue("5s") Duration actionDeadline,
        @DefaultValue("16") int defaultSubscriptionsPerUser,
        @DefaultValue("64") int hardSubscriptionsPerUser,
        @DefaultValue("120") int subscribesPerUserPerMinute,
        @DefaultValue("8") int streamsPerUser,
        @DefaultValue("2000") int streamsPerInstance,
        @DefaultValue("20s") Duration heartbeat,
        @DefaultValue("1h") Duration streamTimeout,
        @DefaultValue("30s") Duration resumeGrace,
        @DefaultValue("256") int replayEvents,
        @DefaultValue("30s") Duration replayAge,
        @DefaultValue("512") int sendQueue,
        @DefaultValue("1000") int maxPendingTimers) {

    /** What one plugin's callers each get per minute: its own ask, or the default, never past the ceiling. */
    int actionsPerUserPerMinute(PluginManifest manifest) {
        Integer asked = manifest.actions() == null ? null : manifest.actions().perUserPerMinute();
        return capped(asked, defaultActionsPerUserPerMinute, hardActionsPerUserPerMinute);
    }

    /** What one plugin's callers together get per minute. */
    int actionsPerPluginPerMinute(PluginManifest manifest) {
        Integer asked = manifest.actions() == null ? null : manifest.actions().perPluginPerMinute();
        return capped(asked, defaultActionsPerPluginPerMinute, hardActionsPerPluginPerMinute);
    }

    /** How many subscriptions one user may hold to this plugin at once. */
    int subscriptionsPerUser(PluginManifest manifest) {
        Integer asked = manifest.channels() == null ? null : manifest.channels().perUserSubscriptions();
        return capped(asked, defaultSubscriptionsPerUser, hardSubscriptionsPerUser);
    }

    private static int capped(Integer asked, int fallback, int ceiling) {
        int wanted = asked == null || asked <= 0 ? fallback : asked;
        return Math.min(wanted, ceiling);
    }
}
