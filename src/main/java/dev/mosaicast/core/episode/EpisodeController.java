// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.web.PagedResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read API over episodes (ARCHITECTURE §6): a feed's episodes (season-filterable), its seasons,
 * one episode's detail, and full-text search. All list endpoints paginate (§13). Anonymous read is fine;
 * access gating (§10) is v1-trivial (everything PUBLIC).
 */
@RestController
public class EpisodeController {

    private final EpisodeQueryService episodes;

    public EpisodeController(EpisodeQueryService episodes) {
        this.episodes = episodes;
    }

    /** Episodes of a feed, optionally filtered by {@code season} (filter state lives in the URL, §6.1). */
    @GetMapping("/api/feeds/{feedId}/episodes")
    public PagedResponse<EpisodeSummary> listByFeed(
            @PathVariable UUID feedId,
            @RequestParam(required = false) Integer season,
            @PageableDefault(size = 20) Pageable pageable) {
        return PagedResponse.of(episodes.listByFeed(feedId, season, unsorted(pageable)), s -> s);
    }

    @GetMapping("/api/feeds/{feedId}/seasons")
    public List<Integer> seasons(@PathVariable UUID feedId) {
        return episodes.seasons(feedId);
    }

    /**
     * The unified site-scope episode feed (§6.1) — the shell's landing feed. All feeds by default; the
     * optional {@code feedId} and {@code season} narrow it and {@code order} ({@code newest}|{@code oldest})
     * sorts it. Filter state lives in the URL.
     */
    @GetMapping("/api/episodes")
    public PagedResponse<EpisodeSummary> list(
            @RequestParam(required = false) UUID feedId,
            @RequestParam(required = false) Integer season,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "newest") String order,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean newest = !"oldest".equalsIgnoreCase(order);
        return PagedResponse.of(episodes.listSite(feedId, season, tag, newest, unsorted(pageable)), s -> s);
    }

    /** Distinct tags (optionally scoped to a feed) — the shell's tag-filter options (§6.1). */
    @GetMapping("/api/tags")
    public List<String> tags(@RequestParam(required = false) UUID feedId) {
        return episodes.tags(feedId);
    }

    /** One episode's detail by its public slug (§6.2). The literal {@code /search} mapping wins over this pattern. */
    @GetMapping("/api/episodes/{slug}")
    public EpisodeDetail detail(@PathVariable String slug) {
        return episodes.detailBySlug(slug);
    }

    /** Previous/next in the feed's canonical sequence (§6.2) — detail nav + player auto-advance. */
    @GetMapping("/api/episodes/{slug}/adjacent")
    public AdjacentEpisodes adjacent(@PathVariable String slug) {
        return episodes.adjacentBySlug(slug);
    }

    /** Full-text episode search over display snapshots (§E1), ranked and paginated. */
    @GetMapping("/api/episodes/search")
    public PagedResponse<EpisodeSummary> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return PagedResponse.of(episodes.search(q, unsorted(pageable)), s -> s);
    }

    /**
     * Drops any client-supplied sort. These endpoints have a server-defined order (canonical episode order,
     * or search relevance), and a client {@code ?sort=} would be spliced into the native/JPQL query and
     * reference a non-existent column — a 500. Only page/size are honored.
     */
    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
