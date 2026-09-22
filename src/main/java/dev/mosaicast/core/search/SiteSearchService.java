// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.search;

import dev.mosaicast.core.episode.EpisodeQueryService;
import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.plugin.PluginExtensions;
import dev.mosaicast.plugin.api.Role;
import java.util.List;
import org.springframework.data.domain.Page;
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
     * <p>The episode section paginates; the plugin sections do not. That asymmetry is the contract's, not a
     * shortcut: a {@code SearchProvider} is handed a limit and returns what it ranked, and the host has no
     * model of a plugin's objects to page through. So a page beyond the first asks only the episode half,
     * and the plugin sections are returned once, with page 0.
     *
     * @param query what the visitor typed, verbatim
     * @param role  the caller's role, or {@code null} for an anonymous visitor — passed to plugins as-is,
     *              because a provider filters its own objects (the host has no model of them)
     * @param page  zero-based; clamped, so a hand-edited URL cannot ask for a negative page
     */
    public SearchResults search(String query, Role role, int page) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            // An empty query matches nothing rather than everything — the rule the schema search already
            // follows, and the one the SDK asks providers to follow.
            return new SearchResults("", List.of(), 0, 0, 0, List.of());
        }
        int requested = Math.max(0, page);
        Page<EpisodeSummary> found = episodes.search(trimmed, PageRequest.of(requested, MAX_EPISODES));
        // Asked once. Re-running every provider for each page of episodes would multiply the search budget
        // by the number of pages a visitor clicks through, for sections that do not move.
        List<SearchResults.PluginSection> plugins = requested == 0
                ? extensions.searchHits(trimmed, role, MAX_PLUGIN_HITS)
                : List.of();
        return new SearchResults(trimmed, found.getContent(), requested,
                found.getTotalPages(), found.getTotalElements(), plugins);
    }

    /** The first page — what every caller wanted before search could paginate. */
    public SearchResults search(String query, Role role) {
        return search(query, role, 0);
    }
}
