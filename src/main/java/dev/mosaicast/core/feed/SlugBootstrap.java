// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeSlugBackfill;
import dev.mosaicast.core.plugin.PluginLoaderService;
import dev.mosaicast.core.plugin.PluginScopeRepartition;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Runs the slug backfills and the plugin-scope repartition <strong>before the HTTP port opens</strong>
 * (ARCHITECTURE §4.1).
 *
 * <p>They used to be {@code ApplicationRunner}s, which Spring Boot invokes from {@code callRunners(...)} —
 * after {@code refreshContext(...)} has already started Tomcat. So on the upgrade that introduced slugs there
 * was a window, however brief, in which the catalog answered requests with {@code "slug": null}: the shell's
 * {@code Home} redirected to {@code /feeds/null}, {@code FeedTabs} rendered dead links, and the follow-up
 * {@code /api/feeds/null} 404'd — while {@code api/types.ts} declares {@code slug: string}, so TypeScript said
 * it could not happen. A restart under load is exactly when that matters and exactly when nobody is watching.
 *
 * <p>{@link SmartInitializingSingleton#afterSingletonsInstantiated()} fires at the end of
 * {@code finishBeanFactoryInitialization}, which precedes {@code finishRefresh} and therefore the web server
 * starting. Flyway has already migrated by then, because the {@code EntityManagerFactory} depends on it.
 *
 * <p>A separate bean rather than the backfills implementing the interface themselves: they are
 * {@code @Transactional}, and Spring's transaction advice is proxy-based — a self-invocation from inside the
 * callback would run with no transaction at all and quietly do nothing. Injected here, they are proxies.
 *
 * <p>Order is a data dependency, not a preference: feed slugs first (episode slugs read the feed title),
 * episode slugs second, then the repartition, which needs both to exist before it can point documents at them.
 *
 * <p>The plugin loader runs last, for the same "before the port opens" reason and one more: a plugin's
 * {@code register(ctx)} may read or seed its own documents, and it must see the repartitioned store rather
 * than the one it was halfway through moving.
 */
@Component
public class SlugBootstrap implements SmartInitializingSingleton {

    private final FeedSlugBackfill feeds;
    private final EpisodeSlugBackfill episodes;
    private final PluginScopeRepartition repartition;
    private final PluginLoaderService plugins;

    public SlugBootstrap(FeedSlugBackfill feeds, EpisodeSlugBackfill episodes,
                         PluginScopeRepartition repartition, PluginLoaderService plugins) {
        this.feeds = feeds;
        this.episodes = episodes;
        this.repartition = repartition;
        this.plugins = plugins;
    }

    @Override
    public void afterSingletonsInstantiated() {
        feeds.backfill();
        episodes.backfill();
        repartition.run();
        plugins.load();
    }
}
