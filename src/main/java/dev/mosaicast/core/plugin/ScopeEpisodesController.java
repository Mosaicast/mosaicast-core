// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.episode.EpisodeSummary;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
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

    /**
     * The most options one request will return.
     *
     * <p>This endpoint is anonymous and was unpaged, so {@code ?type=site&id=main} loaded every visible
     * {@code EpisodeRef} plus its {@code episode_display} JSONB snapshot, on every call, with no auth and no
     * cost to the caller. On a large catalogue a handful of concurrent requests is hundreds of megabytes of
     * transient heap and a full scan each. Every other list surface in the codebase caps at 200
     * ({@code PluginDataController}, {@code AdminLogController}); this one now agrees with them.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final FeedAccessImpl feeds;

    public ScopeEpisodesController(FeedAccessImpl feeds) {
        this.feeds = feeds;
    }

    @GetMapping("/api/plugins/scope-episodes")
    public List<EpisodeOption> episodesIn(@RequestParam String type, @RequestParam String id,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "200") int size,
                                          Authentication authentication) {
        ScopeType scopeType;
        try {
            scopeType = ScopeType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown scope type: " + type);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        // For the viewer: a quiet planned episode is in the list for a podcaster or an admin, and nobody else
        // (SDK PluginContext.episodes, core#258) — the same rule every other surface applies.
        boolean quiet = dev.mosaicast.core.episode.Previews.canSeeQuiet(authentication);
        return feeds.summariesIn(new Scope(scopeType, id), pageable, quiet).stream()
                .map(s -> new EpisodeOption(s.slug(), label(s)))
                .toList();
    }

    /**
     * A human label: {@code S01E06 · <title>} (title truncated), {@code S05 · <title>} for a season's
     * unnumbered episode, {@code E6 · <title>} for a numbered one outside any season, or just the title.
     *
     * <p>The season-only case used to fall through to the bare title, so a season-5 prologue was labelled as
     * though it belonged to no season at all (core#248).
     */
    static String label(EpisodeSummary s) {
        String title = truncate(s.title());
        if (s.season() != null && s.episodeNo() != null) {
            return "S%02dE%02d · %s".formatted(s.season(), s.episodeNo(), title);
        }
        if (s.season() != null) {
            return "S%02d · %s".formatted(s.season(), title);
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
