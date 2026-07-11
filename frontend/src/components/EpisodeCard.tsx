// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import type { EpisodeSummary } from '../api/types';
import { usePlayer } from '../player/PlayerContext';
import { formatDate, formatDuration } from '../util/format';
import { MosaicCover } from './MosaicCover';
import { SlotRegion } from './SlotRegion';

/**
 * A wide episode card for the feed (mockup, §6). Shows the generative cover, title (→ detail), season/
 * episode + date + runtime (from the feed snapshot, §4.2), and a play affordance. Non-published states get
 * a stub: PLANNED → an "upcoming" chip; TIER-gated → a lock stub (real gating is v2). Hosts the empty
 * `card` plugin slot region for E5.
 */
export function EpisodeCard({ episode }: { episode: EpisodeSummary }) {
  const { t, i18n } = useTranslation();
  const { play } = usePlayer();

  const upcoming = episode.status === 'PLANNED';
  const locked = episode.access === 'TIER';
  const playable = episode.hasAudio && !upcoming && !locked;

  const seasonEp =
    episode.season != null && episode.episodeNo != null
      ? `S${episode.season} · E${episode.episodeNo}`
      : null;

  return (
    <article className={`mc-card${upcoming ? ' mc-card--upcoming' : ''}${locked ? ' mc-card--locked' : ''}`}>
      <div className="mc-card__cover">
        <MosaicCover id={episode.id} />
        {playable && (
          <button
            type="button"
            className="mc-card__play"
            onClick={() => play({ id: episode.id, title: episode.title })}
            aria-label={t('player.play')}
          >
            ▶
          </button>
        )}
      </div>

      <div className="mc-card__body">
        <h3 className="mc-card__title">
          <Link to={`/episodes/${episode.id}`}>{episode.title}</Link>
        </h3>
        <div className="mc-card__meta mc-muted">
          {seasonEp && <span>{seasonEp}</span>}
          {upcoming ? (
            <span className="mc-chip">{t('card.upcoming')}</span>
          ) : (
            <>
              {episode.publishedAt && <span>{formatDate(episode.publishedAt, i18n.language)}</span>}
              {episode.durationSeconds != null && <span>{formatDuration(episode.durationSeconds)}</span>}
            </>
          )}
          {locked && <span className="mc-chip mc-chip--lock">{t('card.locked')}</span>}
        </div>

        {/* Compact plugin renderings (E5) mount here. */}
        <SlotRegion name="card" />
      </div>
    </article>
  );
}
