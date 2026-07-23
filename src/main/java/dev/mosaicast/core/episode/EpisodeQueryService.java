// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side queries over episodes (ARCHITECTURE §6). Resolves the display for each ref from the feed
 * snapshot — or the provisional display while PLANNED (§4.3) — so callers get one uniform presentation
 * regardless of lifecycle. WITHDRAWN episodes are excluded from listings and detail (they exist only so
 * plugin data and feeds do not orphan).
 */
@Service
@Transactional(readOnly = true)
public class EpisodeQueryService {

    private static final DisplaySnapshot EMPTY =
            new DisplaySnapshot("", "", null, null, null, null, null, null, null);

    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final EpisodeTagRepository episodeTags;

    public EpisodeQueryService(EpisodeRefRepository refs, EpisodeDisplayRepository displays,
                               EpisodeTagRepository episodeTags) {
        this.refs = refs;
        this.displays = displays;
        this.episodeTags = episodeTags;
    }

    /** Episodes visible in a feed, optionally filtered by season, in canonical order (paginated). */
    public Page<EpisodeSummary> listByFeed(UUID feedId, Integer season, Pageable pageable) {
        Page<EpisodeRef> page = refs.findVisible(feedId, season, pageable);
        Map<UUID, DisplaySnapshot> snapshots = snapshotsFor(page.getContent());
        return page.map(ref -> EpisodeSummary.from(ref, resolveDisplay(ref, snapshots)));
    }

    /**
     * The unified site-scope episode feed (§6.1): visible episodes across all feeds (or one, when
     * {@code feedId} is given), optionally season-filtered, newest- or oldest-first. This is the shell's
     * landing feed — feed/season/order are filters, not separate pages. Batches the snapshot load and
     * preserves the DB order (upcoming first, then by publish date).
     */
    public Page<EpisodeSummary> listSite(UUID feedId, Integer season, String tag, boolean newest, Pageable pageable) {
        Page<UUID> ids = refs.findSiteVisibleIds(feedId, season, tag, newest, pageable);
        return new PageImpl<>(summariesInOrder(ids.getContent()), pageable, ids.getTotalElements());
    }

    /** Distinct seasons present in a feed (§4.4). */
    public List<Integer> seasons(UUID feedId) {
        return refs.findSeasons(feedId);
    }

    /** Distinct tags across visible episodes, optionally scoped to a feed (§6.1) — the tag filter options. */
    public List<String> tags(UUID feedId) {
        return episodeTags.distinctTags(feedId);
    }

    /**
     * The previous/next episode for the detail page and the player's auto-advance. Navigation follows the
     * feed's <em>release order</em> (publishedAt) — the same order the browsable feed uses — so prev/next
     * match what the listener sees and do not depend on episode numbers, which some hosts (e.g. Acast) leave
     * unset in the RSS. {@code prev} is the previously-released episode, {@code next} the next-released one.
     * A missing/withdrawn id is a 404. The neighbours are found in the feed's ordered list (fine for feed
     * sizes in v1).
     *
     * <p>Deviates from the season/episode "canonical sequence" wording in ARCHITECTURE §6.2: episode numbers
     * are too often absent or inconsistent in real feeds to drive navigation reliably.
     */
    public AdjacentEpisodes adjacent(UUID refId) {
        return adjacentOf(refs.findVisibleById(refId)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + refId)));
    }

    /** Detail for one episode by its public slug (§6.2). */
    public EpisodeDetail detailBySlug(String slug) {
        EpisodeRef ref = refs.findVisibleBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + slug));
        return EpisodeDetail.from(ref, resolveDisplay(ref, snapshotsFor(List.of(ref))));
    }

    /** Previous/next by the current episode's public slug (§6.2). */
    public AdjacentEpisodes adjacentBySlug(String slug) {
        return adjacentOf(refs.findVisibleBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + slug)));
    }

    private AdjacentEpisodes adjacentOf(EpisodeRef ref) {
        UUID refId = ref.getId();
        // Same release order (oldest→newest) as the browsable feed, so navigation is consistent with the list
        // and independent of episode numbers: index-1 is the previously-released episode, index+1 the next.
        List<UUID> ordered = refs.findSiteVisibleIds(
                ref.getFeedId(), null, null, false, Pageable.unpaged()).getContent();
        int index = ordered.indexOf(refId);
        UUID prevId = index > 0 ? ordered.get(index - 1) : null;
        UUID nextId = index >= 0 && index < ordered.size() - 1 ? ordered.get(index + 1) : null;
        List<EpisodeRef> neighbours = refs.findAllById(
                java.util.stream.Stream.of(prevId, nextId).filter(java.util.Objects::nonNull).toList());
        Map<UUID, EpisodeRef> byId = neighbours.stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeRef::getId, Function.identity()));
        EpisodeRef prev = prevId == null ? null : byId.get(prevId);
        EpisodeRef next = nextId == null ? null : byId.get(nextId);
        Map<UUID, DisplaySnapshot> snapshots =
                snapshotsFor(java.util.stream.Stream.of(prev, next).filter(java.util.Objects::nonNull).toList());
        return new AdjacentEpisodes(summaryOrNull(prev, snapshots), summaryOrNull(next, snapshots));
    }

    private EpisodeSummary summaryOrNull(EpisodeRef ref, Map<UUID, DisplaySnapshot> snapshots) {
        return ref == null ? null : EpisodeSummary.from(ref, resolveDisplay(ref, snapshots));
    }

    /** Loads refs for a list of ids and maps them to summaries preserving the id order. */
    private List<EpisodeSummary> summariesInOrder(List<UUID> ids) {
        List<EpisodeRef> found = refs.findAllById(ids);
        Map<UUID, EpisodeRef> byId = found.stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeRef::getId, Function.identity()));
        Map<UUID, DisplaySnapshot> snapshots = snapshotsFor(found);
        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(ref -> EpisodeSummary.from(ref, resolveDisplay(ref, snapshots)))
                .toList();
    }

    /** Full detail for one episode, or a 404 for a missing/withdrawn ref (§6.6 — real 404s). */
    public EpisodeDetail detail(UUID refId) {
        EpisodeRef ref = refs.findVisibleById(refId)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + refId));
        return EpisodeDetail.from(ref, resolveDisplay(ref, snapshotsFor(List.of(ref))));
    }

    /**
     * The display snapshot for a visible episode by id — the same presentation the shell renders (§4.2),
     * for {@code FeedAccess.display}. Returns the empty snapshot (never null) for a missing, withdrawn or
     * disabled-feed ref, so a plugin never sees a null.
     */
    public DisplaySnapshot displayFor(UUID refId) {
        return refs.findVisibleById(refId)
                .map(ref -> resolveDisplay(ref, snapshotsFor(List.of(ref))))
                .orElse(EMPTY);
    }

    /** The display snapshot for a visible episode by its public slug — the slug counterpart of {@link #displayFor}. */
    public DisplaySnapshot displayForSlug(String slug) {
        return refs.findVisibleBySlug(slug)
                .map(ref -> resolveDisplay(ref, snapshotsFor(List.of(ref))))
                .orElse(EMPTY);
    }

    /** Full-text search over display snapshots, ranked by relevance (paginated). */
    public Page<EpisodeSummary> search(String query, Pageable pageable) {
        Page<UUID> ids = displays.search(query, pageable);
        List<EpisodeRef> found = refs.findAllById(ids.getContent());
        Map<UUID, EpisodeRef> byId = found.stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeRef::getId, Function.identity()));
        Map<UUID, DisplaySnapshot> snapshots = snapshotsFor(found);
        List<EpisodeSummary> ordered = ids.getContent().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(ref -> EpisodeSummary.from(ref, resolveDisplay(ref, snapshots)))
                .toList();
        return new PageImpl<>(ordered, pageable, ids.getTotalElements());
    }

    /**
     * Resolves the presentation for a ref from a pre-fetched snapshot map (§4.2): the feed snapshot for a
     * PUBLISHED episode, or the provisional display while PLANNED (§4.3), else an empty snapshot. Batching
     * the snapshot load keeps a page of episodes to two queries instead of an N+1.
     */
    public DisplaySnapshot resolveDisplay(EpisodeRef ref, Map<UUID, DisplaySnapshot> snapshots) {
        if (ref.getStatus() == EpisodeStatus.PLANNED && ref.getProvisionalDisplay() != null) {
            return ref.getProvisionalDisplay();
        }
        return snapshots.getOrDefault(ref.getId(), EMPTY);
    }

    /** One batched snapshot load for a set of refs, keyed by ref id. */
    private Map<UUID, DisplaySnapshot> snapshotsFor(List<EpisodeRef> refList) {
        List<UUID> ids = refList.stream().map(EpisodeRef::getId).toList();
        return displays.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        EpisodeDisplay::getEpisodeRefId, EpisodeDisplay::getSnapshot));
    }
}
