// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The host-resolved episode ids for a scope (ARCHITECTURE §6.1/§7.5), which the shell puts on
 * {@code ctx.episodes} when mounting a plugin Web Component. The host owns scope resolution and access
 * filtering (reusing {@link FeedAccess}); plugins never resolve scopes themselves. Public read.
 */
@RestController
public class ScopeEpisodesController {

    private final FeedAccess feeds;

    public ScopeEpisodesController(FeedAccess feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/api/plugins/scope-episodes")
    public List<String> episodesIn(@RequestParam String type, @RequestParam String id) {
        ScopeType scopeType;
        try {
            scopeType = ScopeType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope type: " + type);
        }
        return feeds.episodesIn(new Scope(scopeType, id));
    }
}
