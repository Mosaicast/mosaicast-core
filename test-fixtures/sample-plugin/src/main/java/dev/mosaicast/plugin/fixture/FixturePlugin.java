// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.fixture;

import dev.mosaicast.plugin.api.PluginBackend;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Criteria;
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
public class FixturePlugin implements PluginBackend {

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
}
