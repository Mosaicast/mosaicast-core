// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.search;

import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Site-wide search (ARCHITECTURE §6, SDK {@code SearchProvider}): core's episodes, plus whatever active
 * plugins contribute about their own content.
 *
 * <p>Before this, {@code /api/episodes/search} knew about episodes and nothing else, and a plugin with
 * searchable content could only grow a second search box on the same site — right for its own data, wrong
 * for a visitor, who then has two places to type the same query and no way to find out the answer is in the
 * other one.
 *
 * <p><strong>Grouped, never merged.</strong> A plugin's {@code score} and Postgres {@code ts_rank} are not
 * on one scale, so interleaving them would produce an order nobody could explain and that changed meaning
 * whenever a plugin changed its scoring. One section per source is honest, and it survives that.
 *
 * <p><strong>A slow or broken plugin costs its own section.</strong> Providers run with a per-provider
 * budget in {@link PluginExtensions}; a section that did not answer in time comes back marked rather than
 * missing, because "this plugin has no results" and "this plugin did not answer" are different answers to
 * the visitor's question.
 */
@Service
public class SiteSearchService {

    /** The most episode hits one query returns — a search page, not a paginated catalogue. */
    public static final int MAX_EPISODES = 20;

    /** The most hits any one plugin section shows. */
    public static final int MAX_PLUGIN_HITS = 10;

    private final EpisodeQueryService episodes;
    private final PluginExtensions extensions;

    public SiteSearchService(EpisodeQueryService episodes, PluginExtensions extensions) {
        this.episodes = episodes;
        this.extensions = extensions;
    }

    /**
     * Everything the site has about {@code query}, in sections.
     *
     * @param query what the visitor typed, verbatim
     * @param role  the caller's role, or {@code null} for an anonymous visitor — passed to plugins as-is,
     *              because a provider filters its own objects (the host has no model of them)
     */
    public SearchResults search(String query, Role role) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            // An empty query matches nothing rather than everything — the rule the schema search already
            // follows, and the one the SDK asks providers to follow.
            return new SearchResults("", List.of(), List.of());
        }
        List<EpisodeSummary> found = episodes
                .search(trimmed, PageRequest.of(0, MAX_EPISODES))
                .getContent();
        return new SearchResults(trimmed, found,
                extensions.searchHits(trimmed, role, MAX_PLUGIN_HITS));
    }
}
