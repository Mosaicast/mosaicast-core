// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';

import { ApiError } from '../api/client';
import type { AdjacentEpisodes, EpisodeDetail } from '../api/types';
import { Cover } from '../components/Cover';
import { useFeeds } from '../components/FeedsContext';
import { useResource } from '../hooks/useResource';
import { SlotRegion } from '../components/SlotRegion';
import { usePlayer } from '../player/PlayerContext';
import { formatDate, formatDuration } from '../util/format';
import { sanitizeFeedHtml } from '../util/sanitize';
import { NotFound } from './Placeholder';

/**
 * The episode detail page (§6.2): hero with play, sanitized show notes, the fixed **previous/next**
 * navigation (core, separate from related), and the empty `main`/`sidebar` plugin slot regions for E5.
 * A missing/withdrawn episode renders the 404 landmark (real 404 — §6.6).
 */
export function EpisodePage() {
  const { slug = '' } = useParams();
  const { t, i18n } = useTranslation();
  const { play } = usePlayer();
  const { titleOf } = useFeeds();

  const { data: episode, error } = useResource<EpisodeDetail>(`/api/episodes/${slug}`);
  const { data: adjacent } = useResource<AdjacentEpisodes>(`/api/episodes/${slug}/adjacent`);

  // A withdrawn or mistyped episode is a real 404, not an empty page (§6.6).
  if (error instanceof ApiError && error.status === 404) {
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
  const safeNotes = sanitizeFeedHtml(episode.description);

  return (
    <section className="mc-page mc-page--detail">
      {/*
        The artwork, blurred, behind the hero. Set as a custom property rather than an <img> so it is
        decoration the browser can skip and assistive tech never announces — the same picture is already
        present, legibly, as the cover beside it.
      */}
      <div
        className="mc-hero"
        style={episode.imageUrl ? ({ '--mc-hero-art': `url("${episode.imageUrl}")` } as CSSProperties) : undefined}
      >
        <div className="mc-hero__art">
          <Cover id={episode.id} imageUrl={episode.imageUrl} size={200} />
        </div>
        <div className="mc-hero__body">
          {episode.author && <p className="mc-hero__author mc-muted">{t('card.by', { author: episode.author })}</p>}
          <h1 className="mc-hero__title">{episode.title}</h1>
          {episode.subtitle && <p className="mc-hero__subtitle mc-muted">{episode.subtitle}</p>}
          <div className="mc-hero__meta">
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
          </div>
          {playable && (
            <button
              type="button"
              className="mc-btn mc-btn--accent mc-btn--lg"
              onClick={() =>
                play({
                  id: episode.id,
                  slug: episode.slug,
                  title: episode.title,
                  audioUrl: episode.audioUrl,
                  imageUrl: episode.imageUrl,
                  feedTitle: titleOf(episode.feedId),
                  season: episode.season,
                  episodeNo: episode.episodeNo,
                })
              }
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
          <SlotRegion name="main" scope={{ type: 'episode', id: episode.slug }} />
        </div>
        <aside className="mc-detail__side">
          <SlotRegion name="sidebar" scope={{ type: 'episode', id: episode.slug }} />
        </aside>
      </div>

      {/* Sequential navigation is core and always shown, separately from anything a related-strategy
          suggests (§6.2) — cards rather than bare links, so "what comes next" is a place to go. */}
      <nav className="mc-prevnext" aria-label={t('episode.sequence')}>
        {adjacent?.prev ? (
          <Link className="mc-prevnext__link" to={`/episodes/${adjacent.prev.slug}`}>
            <span className="mc-prevnext__dir mc-muted">← {t('episode.previous')}</span>
            <span className="mc-prevnext__title">{adjacent.prev.title}</span>
          </Link>
        ) : (
          <span />
        )}
        {adjacent?.next ? (
          <Link className="mc-prevnext__link mc-prevnext__link--next" to={`/episodes/${adjacent.next.slug}`}>
            <span className="mc-prevnext__dir mc-muted">{t('episode.next')} →</span>
            <span className="mc-prevnext__title">{adjacent.next.title}</span>
          </Link>
        ) : (
          <span />
        )}
      </nav>
    </section>
  );
}
