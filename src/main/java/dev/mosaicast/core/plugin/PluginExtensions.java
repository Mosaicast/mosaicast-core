// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.search.SearchResults;
import dev.mosaicast.plugin.api.OgMeta;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.SearchHit;
import dev.mosaicast.plugin.api.SearchProvider;
import dev.mosaicast.plugin.api.ShareMetadataProvider;
import dev.mosaicast.plugin.api.SitemapProvider;
import dev.mosaicast.plugin.api.SitemapUrl;
import dev.mosaicast.plugin.api.UserDataHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The host's side of the <em>optional</em> plugin extension points (ARCHITECTURE §7.4): share metadata for a
 * deep link (§6.4), sitemap entries (§6.6) and search hits (§6). Most plugins implement none of them, so "no
 * provider" and "provider found nothing" are both ordinary outcomes that fall back to site-level behaviour.
 *
 * <p>Every call into plugin code is isolated: a provider that throws is logged and skipped, exactly like a
 * scheduled task ({@link PluginScheduler}) — a plugin must not be able to break a page render or the sitemap.
 * Switched-off plugins are never asked, so disabling a plugin also removes its URLs from the sitemap.
 */
@Service
public class PluginExtensions {

    private static final Logger log = LoggerFactory.getLogger(PluginExtensions.class);

    /**
     * How long one plugin gets to answer a search before its section is abandoned.
     *
     * <p>Search is the first extension point that runs <em>on a visitor's request</em> rather than on a
     * render the host controls or a nightly sitemap, so a provider that hangs would hang the search page.
     * The budget is per provider and the query continues without it — the same reasoning as the doc-store
     * rate limit: a plugin's failure costs the plugin's section, not the site.
     */
    private static final Duration SEARCH_BUDGET = Duration.ofMillis(800);

    private final PluginLoaderService plugins;

    /**
     * Threads for the search budget. Virtual, because these are almost entirely blocked on a plugin's own
     * database query and there is one task per installed plugin per search — a pooled thread each would be
     * a pool sized by how many plugins an operator happened to install.
     */
    private final ExecutorService searchThreads = Executors.newVirtualThreadPerTaskExecutor();

    public PluginExtensions(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    /**
     * Search hits from every active plugin that implements {@link SearchProvider}, one section per plugin.
     *
     * <p>Three things this does that a plain loop would not:
     *
     * <ul>
     *   <li><strong>Bounds each provider</strong> ({@link #SEARCH_BUDGET}). A section that ran out of time
     *       is returned <em>marked</em> rather than dropped, because "found nothing" and "did not answer"
     *       are different answers to the visitor.</li>
     *   <li><strong>Resolves the URL itself.</strong> A hit names a subpath; the host turns it into
     *       {@code /p/<pluginId>/<subpath>}, so a plugin cannot address the site or another plugin — the
     *       same containment {@code ctx.route.navigate} and the sitemap contribution already have.</li>
     *   <li><strong>Truncates to the limit it asked for.</strong> The SDK asks providers to bound their own
     *       queries; a provider that ignores that must not be able to fill the page.</li>
     * </ul>
     *
     * <p><strong>What it deliberately does not do is filter by access.</strong> The host has no model of a
     * plugin's objects — it cannot know that a row has a {@code published} flag — so the role is passed
     * through and the provider is responsible. The SDK states that plainly; this is the one place in the
     * contract where the host cannot be the one to resolve access.
     *
     * @param query the visitor's query, verbatim
     * @param role  the caller's role, or {@code null} for an anonymous visitor
     * @param limit the most hits per plugin
     */
    public List<SearchResults.PluginSection> searchHits(String query, Role role, int limit) {
        List<SearchResults.PluginSection> sections = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            String pluginId = registration.id();
            List<SearchProvider> providers = plugins.extensions(SearchProvider.class, pluginId);
            if (providers.isEmpty()) {
                continue;
            }
            String name = registration.manifest() == null || registration.manifest().name() == null
                    ? pluginId : registration.manifest().name();
            Timed<List<SearchResults.Hit>> answered = withBudget(pluginId,
                    () -> collect(providers, pluginId, query, role, limit));
            List<SearchResults.Hit> hits = answered.value() == null ? List.of() : answered.value();
            if (hits.isEmpty() && !answered.timedOut()) {
                continue;
            }
            sections.add(new SearchResults.PluginSection(pluginId, name, hits, answered.timedOut()));
        }
        return sections;
    }

    /** Runs one plugin's providers, resolving each hit's subpath into a URL inside its own namespace. */
    private List<SearchResults.Hit> collect(List<SearchProvider> providers, String pluginId, String query,
                                            Role role, int limit) {
        List<SearchResults.Hit> hits = new ArrayList<>();
        for (SearchProvider provider : providers) {
            List<SearchHit> found = provider.search(query, role, limit);
            if (found == null) {
                continue;
            }
            for (SearchHit hit : found) {
                if (hit == null || hit.title() == null || hit.title().isBlank()) {
                    continue;
                }
                hits.add(new SearchResults.Hit(href(pluginId, hit.subpath()), hit.title(),
                        hit.snippet() == null ? "" : hit.snippet()));
                if (hits.size() == limit) {
                    return hits;
                }
            }
        }
        return hits;
    }

    /**
     * A hit's URL: always inside {@code /p/<pluginId>/}.
     *
     * <p>Cleaned the way {@code ctx.route.navigate} cleans a subpath, and for the same reason — a leading
     * slash or a {@code ..} segment must not be able to point a search result at a core route.
     */
    private static String href(String pluginId, String subpath) {
        String cleaned = subpath == null ? "" : String.join("/",
                java.util.Arrays.stream(subpath.split("/"))
                        .filter(segment -> !segment.isEmpty() && !".".equals(segment) && !"..".equals(segment))
                        .toList());
        return cleaned.isEmpty() ? "/p/" + pluginId : "/p/" + pluginId + "/" + cleaned;
    }

    /** Runs {@code task} with the search budget, reporting a timeout rather than throwing it. */
    private <T> Timed<T> withBudget(String pluginId, Callable<T> task) {
        Future<T> future = searchThreads.submit(task);
        try {
            return new Timed<>(future.get(SEARCH_BUDGET.toMillis(), TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("SearchProvider of plugin '{}' exceeded its {}ms budget", pluginId,
                    SEARCH_BUDGET.toMillis());
            return new Timed<>(null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Timed<>(null, true);
        } catch (Exception e) {
            // A throwing provider is skipped like any other extension point: a plugin must not be able to
            // break the search page for the rest of the site.
            log.warn("SearchProvider of plugin '{}' failed: {}", pluginId, e.getMessage());
            return new Timed<>(null, false);
        }
    }

    /**
     * Asks one plugin to erase everything it holds about a user (ARCHITECTURE §12, SDK
     * {@code UserDataHandler}).
     *
     * <p>Unlike every other call in this class, a failure is <strong>not</strong> swallowed. Elsewhere a
     * broken plugin costs its own sitemap entries or its own OpenGraph tags, and the page still renders. A
     * failed erasure means personal data is still there while the person has been told it is gone, so it
     * propagates and the caller records a debt.
     *
     * <p>A plugin with no handler is a success with nothing to do: most plugins hold no personal data, and
     * not implementing the interface is how they say so.
     *
     * @param pluginId the plugin to ask — must be active; an inactive one cannot be asked at all
     * @param userId   the user whose data goes
     */
    public void eraseUserData(String pluginId, String userId) {
        for (UserDataHandler handler : plugins.extensions(UserDataHandler.class, pluginId)) {
            handler.eraseUser(userId);
        }
    }

    /** A provider's answer, plus whether it arrived in time. */
    private record Timed<T>(T value, boolean timedOut) {
    }

    /**
     * Share metadata for {@code /p/{pluginId}/{subpath}}, or empty to fall back to site-level OpenGraph.
     * The first provider with an answer wins; a plugin declaring several is unusual but legal.
     *
     * @param pluginId the plugin owning the deep link
     * @param subpath  the path below {@code /p/{pluginId}/}; never null, may be empty (the plugin root)
     */
    public Optional<OgMeta> shareMetadata(String pluginId, String subpath) {
        for (ShareMetadataProvider provider : plugins.extensions(ShareMetadataProvider.class, pluginId)) {
            try {
                Optional<OgMeta> meta = provider.metaFor(subpath == null ? "" : subpath);
                if (meta != null && meta.isPresent()) {
                    return meta;
                }
            } catch (Exception e) {
                log.warn("ShareMetadataProvider of plugin '{}' failed for '{}': {}",
                        pluginId, subpath, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Sitemap entries contributed by every active plugin. Locations are validated to sit under that plugin's
     * own {@code /p/{pluginId}/} namespace — a plugin cannot inject URLs for the site or for another plugin.
     */
    public List<SitemapUrl> sitemapUrls() {
        List<SitemapUrl> urls = new ArrayList<>();
        for (PluginRegistration registration : plugins.allActive()) {
            String pluginId = registration.id();
            String prefix = "/p/" + pluginId + "/";
            for (SitemapProvider provider : plugins.extensions(SitemapProvider.class, pluginId)) {
                try {
                    List<SitemapUrl> provided = provider.urls();
                    if (provided == null) {
                        continue;
                    }
                    provided.stream()
                            .filter(url -> url != null && url.loc() != null)
                            .filter(url -> url.loc().equals(prefix.substring(0, prefix.length() - 1))
                                    || url.loc().startsWith(prefix))
                            .forEach(urls::add);
                } catch (Exception e) {
                    log.warn("SitemapProvider of plugin '{}' failed: {}", pluginId, e.getMessage());
                }
            }
        }
        return urls;
    }
}
