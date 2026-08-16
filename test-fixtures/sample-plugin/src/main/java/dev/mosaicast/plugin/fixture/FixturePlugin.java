// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.PluginBackend;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Criteria;
import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import java.util.List;
import dev.mosaicast.plugin.api.SchemaStore;
import dev.mosaicast.plugin.api.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.pf4j.Extension;

/**
 * A minimal plugin backend for the host's plugin-loading integration test. Exercises the real contract:
 * reads config, seeds a site-scoped document (so the test can assert the doc store round-trips through the
 * HTTP surface), counts episodes in scope, and registers a scheduled task. Deliberately tiny — its job is
 * to prove the host loads a genuine PF4J extension and wires a working {@link PluginContext}.
 */
@Extension
public class FixturePlugin implements PluginBackend, SitemapProvider {

    /**
     * Set by {@link #register(PluginContext)} — deliberately a <strong>plain instance field</strong>, which
     * is how the SDK contract reads and how an author would naturally write it.
     *
     * <p>PF4J's default {@code ExtensionFactory} builds a separate instance per extension point, so this
     * field was null when {@link #urls()} ran and the plugin's sitemap entries silently vanished. The host
     * now installs {@code SingletonExtensionFactory}; this field staying non-static is what keeps that
     * honest, and {@link #urls()} below is the regression test's hook.
     */
    private PluginContext registered;

    @Override
    public void register(PluginContext ctx) {
        int refresh = ctx.config().get("refreshIntervalMinutes", Integer.class, 30);
        ctx.store().get(Scope.site(), "greeting", String.class)
                .orElseGet(() -> {
                    ctx.store().put(Scope.site(), "greeting", "hello from fixture");
                    return "hello from fixture";
                });
        int episodeCount = ctx.feeds().episodesIn(Scope.site()).size();
        ctx.store().put(Scope.site(), "episode-count", episodeCount);
        ctx.onSchedule(Duration.ofMinutes(Math.max(1, refresh)), () -> {
            // Nothing to do on tick in the fixture; registering it proves onSchedule accepts the task.
        });
        this.registered = ctx;
        seedSchema(ctx);
    }

    /**
     * Writes one row through {@link SchemaStore} when the manifest declared a schema.
     *
     * <p>Seeding inside {@code register} is what a real plugin does, and it is the part worth exercising:
     * the tables have to exist by the time the host hands over the context, not merely by the time the
     * plugin's first request arrives.
     */
    private static void seedSchema(PluginContext ctx) {
        SchemaStore schema = ctx.schema();
        if (schema == null) {
            return;
        }
        if (schema.count("page", Criteria.where("slug", Criteria.Op.EQ, "seeded")) > 0) {
            return;
        }
        schema.insert("page", Map.of(
                "slug", "seeded",
                "title", "Seeded page",
                "markdown", "A lighthouse keeper writes about fog.",
                "views", 7L,
                "rating", 4.5,
                "published", true,
                "updatedAt", Instant.parse("2026-03-04T10:00:00Z")));
    }

    /**
     * Contributes one URL, and only when {@link #register(PluginContext)} has run on <em>this</em> object.
     *
     * <p>That condition is the whole point: with PF4J's default per-lookup factory the host asks a different
     * instance than the one it registered, {@link #registered} is null here, and the entry disappears
     * without an error anywhere. The path is hardcoded to the {@code good} fixture's namespace; for the
     * other fixtures sharing this jar it is dropped by the host's own namespace check, which is tested
     * separately.
     */
    @Override
    public List<SitemapUrl> urls() {
        return registered == null ? List.of() : List.of(new SitemapUrl("/p/good/ctx-seen", null));
    }
}
