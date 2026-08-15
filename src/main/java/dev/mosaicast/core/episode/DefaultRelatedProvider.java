// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.feed.TitleSimilarity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The v1 related strategy (ARCHITECTURE §6.3): deterministic, explainable, no ML.
 *
 * <p><strong>Pinned first, then a weighted blend.</strong> A podcaster's pin is an answer, not a signal, so
 * pins are emitted in their curated order before anything is scored — and they are not scored themselves,
 * because a pin that placed third by score would make the curation look broken.
 *
 * <p>The three derived signals, in the order §6.3 lists them:
 * <ul>
 *   <li><strong>Same season</strong> — the strongest, because a season is a deliberate grouping the
 *       podcaster already made. Weighted by how near in episode number, so within a season the neighbours
 *       come first.</li>
 *   <li><strong>Shared tags</strong> — the topical signal, and the one that reaches across seasons and
 *       feeds. Each shared tag adds, with diminishing returns: two episodes sharing five tags are usually
 *       the same kind of episode rather than five times as related.</li>
 *   <li><strong>Fuzzy title</strong> — the weakest, and deliberately so. It catches explicit series ("Mailbag
 *       #3", "Part Two") and little else; scoring it highly would surface episodes that merely share a
 *       naming convention.</li>
 * </ul>
 *
 * <p>Same-feed adds a small bonus rather than being a requirement: a site unifies several feeds (§6.1), and
 * a news-feed episode about the same topic is a good answer. But when everything else ties, staying in the
 * feed the listener is already in is the better guess.
 *
 * <p><strong>Excluded:</strong> the episode itself, anything not {@code PUBLISHED} — a {@code PLANNED}
 * episode has no audio and a {@code WITHDRAWN} one is gone — and anything in a disabled feed. §6.3 also
 * allows locked episodes as a lock stub; v1 is all {@code PUBLIC} (§10), so there is nothing to stub yet.
 *
 * <p><strong>Computed on request and cached</strong> (§6.3), invalidated wholesale when the episode set
 * changes. Wholesale because a new episode can change the answer for any episode that shares a tag with it,
 * and working out which is more expensive than recomputing the few that get asked for.
 */
@Service
public class DefaultRelatedProvider implements RelatedProvider {

    /**
     * How many recent episodes are scored. §6.3 wants this computed live, and scoring an unbounded catalogue
     * on a page view is how that becomes slow on exactly the sites that have the most to relate. Everything
     * beyond this window is still reachable through a pin, which is the override for the case where a human
     * knows better than a window.
     */
    private static final int CANDIDATE_WINDOW = 500;

    private static final double WEIGHT_SAME_SEASON = 3.0;
    private static final double WEIGHT_SHARED_TAG = 2.0;
    private static final double WEIGHT_TITLE = 1.0;
    private static final double WEIGHT_SAME_FEED = 0.5;

    /** Below this, a title pair is coincidence rather than a series; §5.3's binding threshold, reused. */
    private static final double TITLE_FLOOR = 0.6;

    private final EpisodeRefRepository refs;
    private final EpisodePinRepository pins;
    private final EpisodeTagRepository tags;
    private final EpisodeQueryService episodes;

    /**
     * Answers computed since the last invalidation, keyed by episode and limit.
     *
     * <p>A plain map rather than Spring's cache abstraction: there is one thing to cache, one event that
     * invalidates it, and no eviction policy worth configuring. Bounded by the number of episodes anyone
     * actually opens between two polls, which is small.
     */
    private final Map<String, List<UUID>> cache = new ConcurrentHashMap<>();

    public DefaultRelatedProvider(EpisodeRefRepository refs, EpisodePinRepository pins,
                                  EpisodeTagRepository tags, EpisodeQueryService episodes) {
        this.refs = refs;
        this.pins = pins;
        this.tags = tags;
        this.episodes = episodes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> related(UUID episodeRefId, int limit) {
        if (episodeRefId == null || limit <= 0) {
            return List.of();
        }
        return cache.computeIfAbsent(episodeRefId + ":" + limit, ignored -> compute(episodeRefId, limit));
    }

    @Override
    public void invalidate() {
        cache.clear();
    }

    private List<UUID> compute(UUID episodeRefId, int limit) {
        EpisodeRef current = refs.findVisibleById(episodeRefId).orElse(null);
        if (current == null) {
            return List.of();
        }

        // Pins are an answer, not a signal: emitted first, in curated order, and never scored.
        Set<UUID> result = new LinkedHashSet<>(pins.findVisiblePinnedIds(episodeRefId));
        if (result.size() >= limit) {
            return List.copyOf(result).subList(0, limit);
        }

        for (Scored scored : score(current, result)) {
            if (result.size() >= limit) {
                break;
            }
            result.add(scored.refId());
        }
        return List.copyOf(result);
    }

    /** The derived candidates, best first, excluding the current episode and anything already pinned. */
    private List<Scored> score(EpisodeRef current, Set<UUID> alreadyChosen) {
        List<EpisodeSummary> candidates = episodes
                .listSite(null, null, null, true, PageRequest.of(0, CANDIDATE_WINDOW))
                .getContent();

        Map<UUID, Integer> sharedTagCounts = sharedTagCounts(current.getId());
        String currentTitle = titleOf(current.getId(), candidates);

        List<Scored> scored = new ArrayList<>();
        for (EpisodeSummary candidate : candidates) {
            if (candidate.id().equals(current.getId()) || alreadyChosen.contains(candidate.id())) {
                continue;
            }
            // PLANNED has no audio and WITHDRAWN is gone; neither is somewhere to send a listener.
            if (candidate.status() != EpisodeStatus.PUBLISHED) {
                continue;
            }
            double score = scoreOne(current, currentTitle, candidate, sharedTagCounts);
            if (score > 0) {
                scored.add(new Scored(candidate.id(), score));
            }
        }
        // Score descending; ties broken by id so the same catalogue always produces the same order rather
        // than whatever the last query happened to return first.
        scored.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : a.refId().compareTo(b.refId());
        });
        return scored;
    }

    private double scoreOne(EpisodeRef current, String currentTitle, EpisodeSummary candidate,
                            Map<UUID, Integer> sharedTagCounts) {
        boolean sameFeed = candidate.feedId().equals(current.getFeedId());

        // The three signals §6.3 names. At least one has to fire, or these two episodes have nothing to do
        // with each other.
        double substantive = 0;

        // A season only means anything within its own feed — "season 2" of two different shows are unrelated.
        if (sameFeed && current.getSeason() != null && current.getSeason().equals(candidate.season())) {
            substantive += WEIGHT_SAME_SEASON * seasonProximity(current.getEpisodeNo(), candidate.episodeNo());
        }

        int shared = sharedTagCounts.getOrDefault(candidate.id(), 0);
        if (shared > 0) {
            // Diminishing returns: the second shared tag confirms the first, the fifth adds little.
            substantive += WEIGHT_SHARED_TAG * (1 + Math.log(shared));
        }

        if (currentTitle != null && candidate.title() != null) {
            double similarity = TitleSimilarity.similarity(currentTitle, candidate.title());
            if (similarity >= TITLE_FLOOR) {
                substantive += WEIGHT_TITLE * similarity;
            }
        }

        // Being in the same feed is a tie-breaker, never a qualifier. Letting it stand alone would make
        // every episode of a show "related" to every other, which on a small site fills the sidebar with
        // the back catalogue and tells a listener nothing. An empty sidebar is a better answer than a
        // dishonest one.
        if (substantive <= 0) {
            return 0;
        }
        return substantive + (sameFeed ? WEIGHT_SAME_FEED : 0);
    }

    /**
     * How close two episodes sit within a season, in {@code (0, 1]}.
     *
     * <p>Adjacent episodes score near 1 and distant ones decay without ever reaching zero — being in the
     * same season is itself worth something, so the far end of a long season still beats an unrelated
     * episode. Missing numbers score a flat middling value: real feeds routinely omit {@code itunes:episode}
     * (§6.2), and treating "unnumbered" as "maximally distant" would bury every trailer and bonus.
     */
    private static double seasonProximity(Integer currentNo, Integer candidateNo) {
        if (currentNo == null || candidateNo == null) {
            return 0.5;
        }
        return 1.0 / (1.0 + Math.abs(currentNo - candidateNo) / 4.0);
    }

    /** How many tags each candidate shares with the current episode. */
    private Map<UUID, Integer> sharedTagCounts(UUID episodeRefId) {
        List<String> own = tags.findTags(episodeRefId);
        if (own.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        // One row per shared tag, so counting occurrences counts shared tags.
        for (UUID refId : tags.findRefIdsByTags(own, episodeRefId)) {
            counts.merge(refId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The current episode's title, taken from the candidate window when it is in there.
     *
     * <p>Saves a second display lookup in the common case (the episode being viewed is usually recent). When
     * it is not in the window — an old episode someone deep-linked — one targeted read is still correct.
     */
    private String titleOf(UUID refId, List<EpisodeSummary> window) {
        for (EpisodeSummary candidate : window) {
            if (candidate.id().equals(refId)) {
                return candidate.title();
            }
        }
        return episodes.displayFor(refId).title();
    }

    private record Scored(UUID refId, double score) {
    }
}
