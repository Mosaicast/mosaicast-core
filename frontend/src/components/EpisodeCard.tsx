// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import type { EpisodeSummary } from '../api/types';
import { usePlayer } from '../player/PlayerContext';
import { listenedFraction } from '../player/progress';
import { formatDate, formatDuration } from '../util/format';
import { Cover } from './Cover';
import { SlotRegion } from './SlotRegion';

/**
 * A wide, one-column episode card (§6.1): a **prominent cover** on the left (real artwork, generative
 * fallback) carrying the play button; on the right the feed title + author, the episode title (+ subtitle),
 * season/episode · date · runtime as chips, and a description excerpt. Non-published states get a stub
 * (PLANNED → upcoming; TIER → members-only). Hosts the `card` slot.
 *
 * Where the visitor got to is drawn along the bottom of the cover. It is the one piece of state that makes
 * a list of episodes feel like *their* list rather than a catalogue, and it costs a `localStorage` read —
 * so it is here, not behind a request.
 */
export function EpisodeCard({ episode, feedTitle }: { episode: EpisodeSummary; feedTitle?: string }) {
  const { t, i18n } = useTranslation();
  const { play } = usePlayer();

  const upcoming = episode.status === 'PLANNED';
  const locked = episode.access === 'TIER';
  const playable = episode.hasAudio && !upcoming && !locked;
  const listened = playable ? listenedFraction(episode.id, episode.durationSeconds) : 0;
  const seasonEp =
    episode.season != null && episode.episodeNo != null
      ? `S${String(episode.season).padStart(2, '0')} · E${String(episode.episodeNo).padStart(2, '0')}`
      : null;

  return (
    <article className={`mc-card${upcoming ? ' mc-card--upcoming' : ''}${locked ? ' mc-card--locked' : ''}`}>
      <div className="mc-card__cover">
        {/* Redundant with the title link — kept as a mouse convenience, hidden from AT/tab order. */}
        <Link to={`/episodes/${episode.slug}`} aria-hidden="true" tabIndex={-1}>
          <Cover id={episode.id} imageUrl={episode.imageUrl} size={168} />
        </Link>
        {playable && (
          <button
            type="button"
            className="mc-card__play"
            onClick={() =>
              play({
                id: episode.id,
                slug: episode.slug,
                title: episode.title,
                imageUrl: episode.imageUrl,
                feedTitle,
                season: episode.season,
                episodeNo: episode.episodeNo,
              })
            }
            aria-label={t('player.play')}
          >
            ▶
          </button>
        )}
        {listened > 0 && (
          <div
            className="mc-card__progress"
            style={{ '--mc-listened': `${Math.round(listened * 100)}%` } as CSSProperties}
            role="img"
            aria-label={t('card.listened', { percent: Math.round(listened * 100) })}
          />
        )}
      </div>

      <div className="mc-card__body">
        <div className="mc-card__top mc-muted">
          {feedTitle && <span className="mc-card__feed">{feedTitle}</span>}
          {episode.author && <span className="mc-card__author">{t('card.by', { author: episode.author })}</span>}
        </div>

        <h3 className="mc-card__title">
          <Link to={`/episodes/${episode.slug}`}>{episode.title}</Link>
        </h3>
        {episode.subtitle && <p className="mc-card__subtitle mc-muted">{episode.subtitle}</p>}

        <div className="mc-card__meta">
          {seasonEp && <span className="mc-chip mc-chip--quiet">{seasonEp}</span>}
          {upcoming ? (
            <span className="mc-chip">{t('card.upcoming')}</span>
          ) : (
            <>
              {episode.publishedAt && (
                <span className="mc-chip mc-chip--quiet">{formatDate(episode.publishedAt, i18n.language)}</span>
              )}
              {episode.durationSeconds != null && (
                <span className="mc-chip mc-chip--quiet">{formatDuration(episode.durationSeconds)}</span>
              )}
            </>
          )}
          {locked && <span className="mc-chip mc-chip--lock">{t('card.locked')}</span>}
        </div>

        {episode.excerpt && <p className="mc-card__excerpt">{episode.excerpt}</p>}

        {/* Compact plugin renderings (E5) mount here. */}
        <SlotRegion name="card" scope={{ type: 'episode', id: episode.slug }} />
      </div>
    </article>
  );
}
