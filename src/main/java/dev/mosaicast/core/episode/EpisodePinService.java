// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.web.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Curating the pinned related episodes a podcaster overrides the computed strategy with (ARCHITECTURE §6.3).
 *
 * <p>Every mutation invalidates the {@link RelatedProvider} cache. A pin that took effect at the next feed
 * poll would look broken to the person who just made it — the point of a pin is that it is the one part of
 * this feature a human controls directly, so it has to behave that way.
 */
@Service
public class EpisodePinService {

    private static final Logger log = LoggerFactory.getLogger(EpisodePinService.class);

    private final EpisodeRefRepository refs;
    private final EpisodePinRepository pins;
    private final EpisodeQueryService episodes;
    private final RelatedProvider related;

    public EpisodePinService(EpisodeRefRepository refs, EpisodePinRepository pins,
                             EpisodeQueryService episodes, RelatedProvider related) {
        this.refs = refs;
        this.pins = pins;
        this.episodes = episodes;
        this.related = related;
    }

    /**
     * The pins under an episode, as summaries the admin UI can render.
     *
     * <p>Unlike the public read, this deliberately does <em>not</em> hide a pin whose target has become
     * invisible: an admin has to be able to see the thing they need to unpin. The public side filters it
     * ({@code EpisodePinRepository.findVisiblePinnedIds}), which is where filtering belongs.
     */
    @Transactional(readOnly = true)
    public List<EpisodeSummary> list(String slug) {
        UUID refId = resolve(slug);
        List<UUID> pinned = pins.findByIdEpisodeRefIdOrderByPositionAsc(refId).stream()
                .map(EpisodePin::getRelatedRefId)
                .toList();
        return summaries(pinned);
    }

    /**
     * Pins an episode under another, at the end of the existing order.
     *
     * @param slug        the episode being curated
     * @param relatedSlug the episode to show under it
     * @throws NotFoundException      if either slug names nothing
     * @throws IllegalArgumentException if the two are the same episode
     */
    @Transactional
    public List<EpisodeSummary> pin(String slug, String relatedSlug) {
        UUID refId = resolve(slug);
        UUID relatedId = resolve(relatedSlug);
        if (refId.equals(relatedId)) {
            // The database rejects this too; catching it here makes it a 400 with a sentence rather than a
            // constraint violation the admin has to interpret.
            throw new IllegalArgumentException("An episode cannot be pinned to itself");
        }
        pins.save(new EpisodePin(refId, relatedId, pins.nextPosition(refId)));
        related.invalidate();
        log.info("Pinned episode {} as related under {}", relatedSlug, slug);
        return list(slug);
    }

    /** Removes a pin. Idempotent: unpinning what is not pinned is a no-op, not an error. */
    @Transactional
    public List<EpisodeSummary> unpin(String slug, String relatedSlug) {
        UUID refId = resolve(slug);
        UUID relatedId = resolve(relatedSlug);
        if (pins.unpin(refId, relatedId) > 0) {
            related.invalidate();
            log.info("Unpinned episode {} from under {}", relatedSlug, slug);
        }
        return list(slug);
    }

    /** Resolves a public slug to a ref id, including episodes a visitor could not see (admin surface). */
    private UUID resolve(String slug) {
        return refs.findBySlug(slug)
                .map(EpisodeRef::getId)
                .orElseThrow(() -> new NotFoundException("Episode not found: " + slug));
    }

    /** Summaries for a list of ids, preserving the given order (the repository does not promise it). */
    List<EpisodeSummary> summaries(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, EpisodeSummary> byId = episodes.summariesByIds(ids).stream()
                .collect(Collectors.toMap(EpisodeSummary::id, Function.identity()));
        List<EpisodeSummary> ordered = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            EpisodeSummary summary = byId.get(id);
            if (summary != null) {
                ordered.add(summary);
            }
        }
        return ordered;
    }
}
