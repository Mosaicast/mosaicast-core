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
        return refs.findVisible(feedId, season, pageable)
                .map(ref -> EpisodeSummary.from(ref, resolveDisplay(ref)));
    }

    /** Distinct seasons present in a feed (§4.4). */
    public List<Integer> seasons(UUID feedId) {
        return refs.findSeasons(feedId);
    }

    /** Full detail for one episode, or a 404 for a missing/withdrawn ref (§6.6 — real 404s). */
    public EpisodeDetail detail(UUID refId) {
        EpisodeRef ref = refs.findById(refId)
                .filter(r -> r.getStatus() != EpisodeStatus.WITHDRAWN)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + refId));
        return EpisodeDetail.from(ref, resolveDisplay(ref));
    }

    /** Full-text search over display snapshots, ranked by relevance (paginated). */
    public Page<EpisodeSummary> search(String query, Pageable pageable) {
        Page<UUID> ids = displays.search(query, pageable);
        Map<UUID, EpisodeRef> byId = refs.findAllById(ids.getContent()).stream()
                .collect(java.util.stream.Collectors.toMap(EpisodeRef::getId, Function.identity()));
        List<EpisodeSummary> ordered = ids.getContent().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(ref -> EpisodeSummary.from(ref, resolveDisplay(ref)))
                .toList();
        return new PageImpl<>(ordered, pageable, ids.getTotalElements());
    }

    /**
     * Resolves the presentation for a ref: the feed snapshot for a PUBLISHED episode, the provisional
     * display while PLANNED (§4.3), or an empty snapshot as a last resort (should not occur in practice).
     */
    public DisplaySnapshot resolveDisplay(EpisodeRef ref) {
        if (ref.getStatus() == EpisodeStatus.PLANNED && ref.getProvisionalDisplay() != null) {
            return ref.getProvisionalDisplay();
        }
        return displays.findById(ref.getId())
                .map(EpisodeDisplay::getSnapshot)
                .or(() -> java.util.Optional.ofNullable(ref.getProvisionalDisplay()))
                .orElse(EMPTY);
    }
}
