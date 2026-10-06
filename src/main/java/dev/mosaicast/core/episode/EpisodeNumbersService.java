// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.episode;

import dev.mosaicast.core.web.CodedBadRequest;
import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A podcaster setting an episode's season and episode number by hand (ARCHITECTURE §4.4, core#264).
 *
 * <p>The case it exists for: a feed cannot carry episode 0 — Apple's spec allows only a non-zero
 * {@code itunes:episode}, so Acast drops it — and the prologue a podcaster numbered S5E0 arrives as season 5
 * with no number. Nothing the feed will ever say can fix that, so the podcaster says it here, and polls leave
 * it alone from then on.
 */
@Service
public class EpisodeNumbersService {

    private static final Logger log = LoggerFactory.getLogger(EpisodeNumbersService.class);

    private final EpisodeRefRepository refs;
    private final RelatedProvider related;

    public EpisodeNumbersService(EpisodeRefRepository refs, RelatedProvider related) {
        this.refs = refs;
        this.related = related;
    }

    /**
     * An episode's numbers: the effective ones, whether they were set by hand, and what the feed says.
     *
     * @param season        the season every surface uses; null when it has none
     * @param episodeNo     the episode number every surface uses; null when it has none
     * @param pinned        whether a podcaster set the two above by hand
     * @param feedSeason    the season the feed last declared
     * @param feedEpisodeNo the episode number the feed last declared
     */
    public record EpisodeNumbers(Integer season, Integer episodeNo, boolean pinned,
                                 Integer feedSeason, Integer feedEpisodeNo) {

        static EpisodeNumbers of(EpisodeRef ref) {
            return new EpisodeNumbers(ref.getSeason(), ref.getEpisodeNo(), ref.isNumbersPinned(),
                    ref.getFeedSeason(), ref.getFeedEpisodeNo());
        }
    }

    /** The numbers of the episode with this slug, including one a visitor could not see (admin surface). */
    @Transactional(readOnly = true)
    public EpisodeNumbers get(String slug) {
        return EpisodeNumbers.of(resolve(slug));
    }

    /**
     * Pins a released episode's numbers — both together, either may be null.
     *
     * @throws NotFoundException  if the slug names nothing
     * @throws CodedBadRequest    if a number is negative
     * @throws ConflictException  if the episode is still planned; its numbers are edited with the plan
     */
    @Transactional
    public EpisodeNumbers pin(String slug, Integer season, Integer episodeNo) {
        if ((season != null && season < 0) || (episodeNo != null && episodeNo < 0)) {
            throw new CodedBadRequest("episode.numbers.negative", "Season and episode number cannot be negative");
        }
        EpisodeRef ref = resolve(slug);
        if (ref.getStatus() == EpisodeStatus.PLANNED) {
            throw new ConflictException("A planned episode's numbers are edited with the plan");
        }
        ref.pinNumbers(season, episodeNo);
        refs.save(ref);
        changed();
        log.info("Episode {} numbered by hand: season {}, episode {} (feed says {}, {})",
                slug, season, episodeNo, ref.getFeedSeason(), ref.getFeedEpisodeNo());
        return EpisodeNumbers.of(ref);
    }

    /** Goes back to the feed's numbers at once. Unpinning an episode that is not pinned changes nothing. */
    @Transactional
    public EpisodeNumbers unpin(String slug) {
        EpisodeRef ref = resolve(slug);
        if (ref.isNumbersPinned()) {
            ref.unpinNumbers();
            refs.save(ref);
            changed();
            log.info("Episode {} back on the feed's numbers", slug);
        }
        return EpisodeNumbers.of(ref);
    }

    private EpisodeRef resolve(String slug) {
        return refs.findBySlug(slug).orElseThrow(() -> new NotFoundException("Episode not found: " + slug));
    }

    /**
     * Related scores season proximity, so its cached answers moved. Dropped after the commit, not inside it:
     * a request landing in between would refill the cache from the old numbers (core#195).
     */
    private void changed() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            related.invalidate();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                related.invalidate();
            }
        });
    }
}
