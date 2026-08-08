// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.feed.FeedService;
import dev.mosaicast.core.web.PagedResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
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
    private final FeedService feeds;

    public EpisodeController(EpisodeQueryService episodes, FeedService feeds) {
        this.episodes = episodes;
        this.feeds = feeds;
    }

    /**
     * Episodes of a feed, optionally filtered by {@code season} (filter state lives in the URL, §6.1). The
     * feed is addressed by its public slug; its UUID still resolves (see {@code FeedService.resolvePublic}).
     */
    @GetMapping("/api/feeds/{feedRef}/episodes")
    public PagedResponse<EpisodeSummary> listByFeed(
            @PathVariable String feedRef,
            @RequestParam(required = false) Integer season,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID feedId = feeds.resolvePublicId(feedRef);
        return PagedResponse.of(episodes.listByFeed(feedId, season, unsorted(pageable)), s -> s);
    }

    @GetMapping("/api/feeds/{feedRef}/seasons")
    public List<Integer> seasons(@PathVariable String feedRef) {
        return episodes.seasons(feeds.resolvePublicId(feedRef));
    }

    /**
     * The unified site-scope episode feed (§6.1) — the shell's landing feed. All feeds by default; the
     * optional {@code feedId} and {@code season} narrow it and {@code order} ({@code newest}|{@code oldest})
     * sorts it. Filter state lives in the URL.
     */
    @GetMapping("/api/episodes")
    public PagedResponse<EpisodeSummary> list(
            @RequestParam(required = false) String feedId,
            @RequestParam(required = false) Integer season,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "newest") String order,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean newest = !"oldest".equalsIgnoreCase(order);
        Optional<UUID> filter = publicFeedFilter(feedId);
        if (filterMatchesNothing(feedId, filter)) {
            return PagedResponse.of(Page.<EpisodeSummary>empty(unsorted(pageable)), s -> s);
        }
        return PagedResponse.of(
                episodes.listSite(filter.orElse(null), season, tag, newest, unsorted(pageable)), s -> s);
    }

    /** Distinct tags (optionally scoped to a feed) — the shell's tag-filter options (§6.1). */
    @GetMapping("/api/tags")
    public List<String> tags(@RequestParam(required = false) String feedId) {
        Optional<UUID> filter = publicFeedFilter(feedId);
        return filterMatchesNothing(feedId, filter) ? List.of() : episodes.tags(filter.orElse(null));
    }

    /**
     * Whether a filter was asked for and names nothing visible.
     *
     * <p>Needed because "no filter" and "a filter that matches nothing" both arrive as an empty
     * {@link Optional} but mean opposite things downstream: passing {@code null} for the second would widen
     * the result to every feed, which is worse than the 404 this replaces.
     */
    private static boolean filterMatchesNothing(String feedRef, Optional<UUID> resolved) {
        return feedRef != null && !feedRef.isBlank() && resolved.isEmpty();
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
    /**
     * Resolves the optional {@code feedId} filter, which the shell now passes as the feed's public slug.
     * A UUID still works, so a link or integration built before slugs keeps filtering correctly.
     *
     * <p>An unresolvable or disabled feed narrows the result to nothing rather than failing the request.
     * This is a <em>filter</em>, not the resource being addressed: routing it through
     * {@code resolvePublicId} made {@code /api/episodes} and {@code /api/tags} 404 in their entirety the
     * moment an admin disabled a feed someone had a filtered link to, where they used to return an empty
     * page. The shell shows the "could not load episodes" banner in place of the empty state, and any
     * integration passing a {@code feedId} breaks outright. {@code EpisodeRefRepository} already excludes
     * disabled feeds' episodes, so filtering by one correctly yields nothing.
     *
     * <p>{@link #listByFeed} and {@link #seasons} keep the 404, because there the feed <em>is</em> the
     * resource: {@code /api/feeds/gone/episodes} names something that does not exist, and answering 200 with
     * an empty page would say it does.
     */
    private Optional<UUID> publicFeedFilter(String feedRef) {
        if (feedRef == null || feedRef.isBlank()) {
            return Optional.empty();
        }
        return feeds.findPublicId(feedRef);
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
