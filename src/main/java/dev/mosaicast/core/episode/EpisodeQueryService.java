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

    private static final DisplaySnapshot EMPTY = new DisplaySnapshot("", "", null, null, null);

    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;

    public EpisodeQueryService(EpisodeRefRepository refs, EpisodeDisplayRepository displays) {
        this.refs = refs;
        this.displays = displays;
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
    public Page<EpisodeSummary> listSite(UUID feedId, Integer season, boolean newest, Pageable pageable) {
        Page<UUID> ids = refs.findSiteVisibleIds(feedId, season, newest, pageable);
        return new PageImpl<>(summariesInOrder(ids.getContent()), pageable, ids.getTotalElements());
    }

    /** Distinct seasons present in a feed (§4.4). */
    public List<Integer> seasons(UUID feedId) {
        return refs.findSeasons(feedId);
    }

    /**
     * The previous/next episode in a feed's canonical sequence (§6.2): season then episode number, so the
     * detail page and the player's auto-advance move through the show in order. A missing/withdrawn id is a
     * 404. The neighbours are found in the feed's ordered visible list (fine for feed sizes in v1).
     */
    public AdjacentEpisodes adjacent(UUID refId) {
        EpisodeRef ref = refs.findById(refId)
                .filter(r -> r.getStatus() != EpisodeStatus.WITHDRAWN)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + refId));
        List<EpisodeRef> ordered = refs.findVisible(ref.getFeedId(), null, Pageable.unpaged()).getContent();
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(refId)) {
                index = i;
                break;
            }
        }
        EpisodeRef prev = index > 0 ? ordered.get(index - 1) : null;
        EpisodeRef next = index >= 0 && index < ordered.size() - 1 ? ordered.get(index + 1) : null;
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
        EpisodeRef ref = refs.findById(refId)
                .filter(r -> r.getStatus() != EpisodeStatus.WITHDRAWN)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + refId));
        return EpisodeDetail.from(ref, resolveDisplay(ref, snapshotsFor(List.of(ref))));
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
