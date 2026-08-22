// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.search;

import dev.mosaicast.core.auth.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-wide search (ARCHITECTURE §6): one query, sections per source.
 *
 * <p>Public, like the episode listing it searches — and the plugin sections it carries are filtered by the
 * plugins themselves, which is the one place in the contract where the host cannot resolve access on a
 * plugin's behalf (SDK {@code SearchProvider}). The caller's role is passed through for exactly that.
 *
 * <p>{@code /api/episodes/search} stays where it is: it is the episode-only query, still used where a
 * caller wants episodes and paging. This is the visitor-facing one.
 */
@RestController
public class SearchController {

    private final SiteSearchService search;

    public SearchController(SiteSearchService search) {
        this.search = search;
    }

    /**
     * Everything the site has about {@code q}.
     *
     * @param q the query as typed; blank matches nothing rather than everything
     */
    @GetMapping("/api/search")
    public SearchResults search(@RequestParam(defaultValue = "") String q, Authentication authentication) {
        return search.search(q, CurrentUser.role(authentication).orElse(null));
    }
}
