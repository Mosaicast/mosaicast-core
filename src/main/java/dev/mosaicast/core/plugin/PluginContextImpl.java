// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.PluginConfig;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import java.time.Duration;

/**
 * The {@link PluginContext} handed to one plugin's {@code register(ctx)} (ARCHITECTURE §7.4). Wires the
 * plugin's hard-scoped {@link DocStore}, its config, the shared host {@link FeedAccess}, and scheduling.
 * {@link #schema()} is always {@code null} in this milestone — schema-declaring plugins are rejected at
 * load (§7.6).
 */
public class PluginContextImpl implements PluginContext {

    private final String pluginId;
    private final DocStore store;
    private final PluginConfig config;
    private final FeedAccess feeds;
    private final PluginScheduler scheduler;
    private int scheduleCount;

    public PluginContextImpl(String pluginId, DocStore store, PluginConfig config, FeedAccess feeds,
                             PluginScheduler scheduler) {
        this.pluginId = pluginId;
        this.store = store;
        this.config = config;
        this.feeds = feeds;
        this.scheduler = scheduler;
    }

    @Override
    public DocStore store() {
        return store;
    }

    @Override
    public SchemaStore schema() {
        return null;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public FeedAccess feeds() {
        return feeds;
    }

    @Override
    public void onSchedule(Duration every, Runnable task) {
        scheduler.schedule(pluginId, scheduleCount++, every, task);
    }
}
