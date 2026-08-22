// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.log.AppLogAppender;
import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.PluginConfig;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import dev.mosaicast.plugin.api.Tags;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PluginContext} handed to one plugin's {@code register(ctx)} (ARCHITECTURE §7.4). Wires the
 * plugin's hard-scoped {@link DocStore}, its config, the shared host {@link FeedAccess}, and scheduling.
 * {@link #schema()} is non-null only for a plugin whose manifest declares one (§7.6) — most declare
 * nothing and get the doc store alone.
 */
public class PluginContextImpl implements PluginContext {

    private final String pluginId;
    private final DocStore store;
    private final SchemaStore schema;
    private final dev.mosaicast.plugin.api.PluginBlobs blobs;
    private final Tags tags;
    private final PluginConfig config;
    private final FeedAccess feeds;
    private final PluginScheduler scheduler;
    private int scheduleCount;

    public PluginContextImpl(String pluginId, DocStore store, SchemaStore schema,
                             dev.mosaicast.plugin.api.PluginBlobs blobs, Tags tags, PluginConfig config,
                             FeedAccess feeds, PluginScheduler scheduler) {
        this.pluginId = pluginId;
        this.store = store;
        this.schema = schema;
        this.blobs = blobs;
        this.tags = tags;
        this.config = config;
        this.feeds = feeds;
        this.scheduler = scheduler;
    }

    @Override
    public DocStore store() {
        return store;
    }

    /**
     * The plugin's logger, named {@code plugin.<pluginId>} by the host (§7.4).
     *
     * <p>The name is the attribution: {@code AppLogAppender} reads the plugin id straight out of it, so an
     * entry stays attributed even when a plugin logs from its own thread or an {@code onSchedule} task,
     * where a thread-local MDC would arrive empty. A plugin that built its own logger would lose that,
     * which is why the SDK tells authors to take this one.
     */
    @Override
    public Logger logger() {
        return LoggerFactory.getLogger(AppLogAppender.loggerNameFor(pluginId));
    }

    /**
     * The plugin's relational store, or {@code null} when its manifest declares no schema.
     *
     * <p>Null rather than an empty implementation, because that is the SDK's documented signal: a plugin
     * that reaches for {@code ctx.schema()} without declaring one has a manifest and a codebase that
     * disagree, and a null teaches that immediately.
     */
    @Override
    public SchemaStore schema() {
        return schema;
    }

    /**
     * File storage, or {@code null} for a plugin whose manifest declares no {@code blobs} block (§11) — the
     * same null-means-not-declared shape as {@link #schema()}.
     */
    @Override
    public dev.mosaicast.plugin.api.PluginBlobs blobs() {
        return blobs;
    }

    /**
     * The site's shared tag vocabulary, or {@code null} for a plugin whose manifest declares no {@code tags}
     * block (§6.1) — the same null-means-not-declared shape as {@link #schema()} and {@link #blobs()}.
     */
    @Override
    public Tags tags() {
        return tags;
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
