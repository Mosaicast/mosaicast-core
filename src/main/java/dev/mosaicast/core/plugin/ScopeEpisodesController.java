// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The host-resolved episodes for a scope (ARCHITECTURE §6.1/§7.5), which the shell puts on {@code ctx.episodes}
 * (the public slugs) + {@code ctx.episodeLabels} (human labels) when mounting a plugin Web Component. The host
 * owns scope resolution, access filtering and labelling; plugins never resolve scopes themselves. Public read.
 */
@RestController
public class ScopeEpisodesController {

    private static final int TITLE_MAX = 60;

    private final FeedAccessImpl feeds;

    public ScopeEpisodesController(FeedAccessImpl feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/api/plugins/scope-episodes")
    public List<EpisodeOption> episodesIn(@RequestParam String type, @RequestParam String id) {
        ScopeType scopeType;
        try {
            scopeType = ScopeType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope type: " + type);
        }
        return feeds.summariesIn(new Scope(scopeType, id)).stream()
                .map(s -> new EpisodeOption(s.slug(), label(s)))
                .toList();
    }

    /** A human label: {@code S01E06 · <title>} (title truncated), or just the title when unnumbered. */
    private static String label(EpisodeSummary s) {
        String title = truncate(s.title());
        if (s.season() != null && s.episodeNo() != null) {
            return "S%02dE%02d · %s".formatted(s.season(), s.episodeNo(), title);
        }
        if (s.episodeNo() != null) {
            return "E%d · %s".formatted(s.episodeNo(), title);
        }
        return title;
    }

    private static String truncate(String title) {
        if (title == null || title.isBlank()) {
            return "(untitled)";
        }
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX).trim() + "…";
    }

    /** An episode choice for a plugin UI: the public slug (the addressing id) plus a display label. */
    public record EpisodeOption(String id, String label) {
    }
}
