// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import DOMPurify from 'dompurify';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';

import { api, ApiError } from '../api/client';
import type { AdjacentEpisodes, EpisodeDetail } from '../api/types';
import { MosaicCover } from '../components/MosaicCover';
import { SlotRegion } from '../components/SlotRegion';
import { usePlayer } from '../player/PlayerContext';
import { formatDate, formatDuration } from '../util/format';
import { NotFound } from './Placeholder';

/**
 * The episode detail page (§6.2): hero with play, sanitized show notes, the fixed **previous/next**
 * navigation (core, separate from related), and the empty `main`/`sidebar` plugin slot regions for E5.
 * A missing/withdrawn episode renders the 404 landmark (real 404 — §6.6).
 */
export function EpisodePage() {
  const { episodeId = '' } = useParams();
  const { t, i18n } = useTranslation();
  const { play } = usePlayer();

  const [episode, setEpisode] = useState<EpisodeDetail | null>(null);
  const [adjacent, setAdjacent] = useState<AdjacentEpisodes | null>(null);
  const [notFound, setNotFound] = useState(false);

  useEffect(() => {
    setNotFound(false);
    setEpisode(null);
    api
      .get<EpisodeDetail>(`/api/episodes/${episodeId}`)
      .then(setEpisode)
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) {
          setNotFound(true);
        }
      });
    api
      .get<AdjacentEpisodes>(`/api/episodes/${episodeId}/adjacent`)
      .then(setAdjacent)
      .catch(() => setAdjacent(null));
  }, [episodeId]);

  if (notFound) {
    return <NotFound />;
  }
  if (!episode) {
    return (
      <section className="mc-page">
        <p className="mc-muted">{t('common.loading')}</p>
      </section>
    );
  }

  const upcoming = episode.status === 'PLANNED';
  const locked = episode.access === 'TIER';
  // Detail carries the real audioUrl (no hasAudio flag) — playability is "has an audio URL".
  const playable = !!episode.audioUrl && !upcoming && !locked;
  const seasonEp =
    episode.season != null && episode.episodeNo != null ? `S${episode.season} · E${episode.episodeNo}` : null;
  // Show notes are feed HTML → sanitize before rendering (no scripts/handlers).
  const safeNotes = episode.description ? DOMPurify.sanitize(episode.description) : '';

  return (
    <section className="mc-page">
      <div className="mc-hero">
        <MosaicCover id={episode.id} size={140} />
        <div className="mc-hero__body">
          <h1 className="mc-hero__title">{episode.title}</h1>
          <div className="mc-hero__meta mc-muted">
            {seasonEp && <span>{seasonEp}</span>}
            {upcoming ? (
              <span className="mc-chip">{t('card.upcoming')}</span>
            ) : (
              <>
                {episode.publishedAt && <span>{formatDate(episode.publishedAt, i18n.language)}</span>}
                {episode.durationSeconds != null && <span>{formatDuration(episode.durationSeconds)}</span>}
              </>
            )}
          </div>
          {playable && (
            <button
              type="button"
              className="mc-btn mc-btn--accent"
              onClick={() => play({ id: episode.id, title: episode.title, audioUrl: episode.audioUrl })}
            >
              ▶ {t('player.play')}
            </button>
          )}
        </div>
      </div>

      <div className="mc-detail">
        <div className="mc-detail__main">
          <div className="mc-plugin" data-slot="main-notes">
            <h2>{t('episode.shownotes')}</h2>
            {safeNotes ? (
              <div className="mc-shownotes" dangerouslySetInnerHTML={{ __html: safeNotes }} />
            ) : (
              <p className="mc-muted">{t('episode.noNotes')}</p>
            )}
          </div>
          {/* Full-width plugin renderings (e.g. bingo) mount here in E5. */}
          <SlotRegion name="main" />
        </div>
        <aside className="mc-detail__side">
          <SlotRegion name="sidebar" />
        </aside>
      </div>

      <nav className="mc-prevnext" aria-label={t('episode.sequence')}>
        {adjacent?.prev ? (
          <Link className="mc-prevnext__link" to={`/episodes/${adjacent.prev.id}`}>
            ← {adjacent.prev.title}
          </Link>
        ) : (
          <span />
        )}
        {adjacent?.next ? (
          <Link className="mc-prevnext__link mc-prevnext__link--next" to={`/episodes/${adjacent.next.id}`}>
            {adjacent.next.title} →
          </Link>
        ) : (
          <span />
        )}
      </nav>
    </section>
  );
}
