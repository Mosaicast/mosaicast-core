// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router-dom';

import { ApiError } from '../api/client';
import type { AdjacentEpisodes, EpisodeDetail, EpisodeSummary } from '../api/types';
import { Cover } from '../components/Cover';
import { useFeeds } from '../components/FeedsContext';
import { Icon } from '../components/Icon';
import { useResource } from '../hooks/useResource';
import { RelatedEpisodes } from '../components/RelatedEpisodes';
import { RelatedPins } from '../components/RelatedPins';
import { ShareButton } from '../components/ShareButton';
import { SlotRegion } from '../components/SlotRegion';
import { usePlayerActions, type PlayableEpisode } from '../player/PlayerContext';
import { formatDate, formatDuration } from '../util/format';
import { sanitizeFeedHtml } from '../util/sanitize';
import { parseTimestamp } from '../util/timestamp';
import { NotFound } from './Placeholder';

/** Whether this episode has audio a visitor may actually play (upcoming/locked ones do not). */
function isPlayable(episode: EpisodeDetail): boolean {
  // Detail carries the real audioUrl (no hasAudio flag) — playability is "has an audio URL".
  return !!episode.audioUrl && episode.status !== 'PLANNED' && episode.access !== 'TIER';
}

/** The detail payload narrowed to what the player needs. */
function toPlayable(episode: EpisodeDetail, feedTitle: string | null | undefined): PlayableEpisode {
  return {
    id: episode.id,
    slug: episode.slug,
    title: episode.title,
    audioUrl: episode.audioUrl,
    imageUrl: episode.imageUrl,
    feedTitle,
    season: episode.season,
    episodeNo: episode.episodeNo,
  };
}

/**
 * The episode detail page (§6.2): hero with play, sanitized show notes, the fixed **previous/next**
 * navigation (core, and deliberately separate from the related list in the sidebar, §6.3), and the
 * `main`/`sidebar` plugin slot regions.
 * A missing/withdrawn episode renders the 404 landmark (real 404 — §6.6).
 *
 * A `?t=` search parameter (§6.4) arms the player at that position: the link is a link to a *moment*, so
 * the timestamp beats the stored listening position, and the page keeps the parameter in the URL so a
 * reload or a back-navigation lands in the same place.
 */
export function EpisodePage() {
  const { slug = '' } = useParams();
  const { t, i18n } = useTranslation();
  const { play } = usePlayerActions();
  const { titleOf } = useFeeds();
  const [params] = useSearchParams();
  // An unparsable value is ignored rather than an error — a mangled timestamp in a forwarded link should
  // still open the episode (§6.4).
  const startAt = parseTimestamp(params.get('t'));

  const { data: episode, error } = useResource<EpisodeDetail>(`/api/episodes/${slug}`);
  const { data: adjacent } = useResource<AdjacentEpisodes>(`/api/episodes/${slug}/adjacent`);
  // Fetched here, not in the widget: pinning changes this list, so the curator and the reader have to
  // share one source of truth about when to re-read it.
  const { data: related, error: relatedError, reload: reloadRelated } =
    useResource<EpisodeSummary[]>(`/api/episodes/${slug}/related`);

  // Arm the player at the shared position once the detail resolves, and once per (episode, timestamp) so a
  // re-render does not yank a listener who has since scrubbed elsewhere.
  //
  // The browser may refuse to start audio without a gesture; that rejection is swallowed in the player and
  // the seek is applied on `loadedmetadata` regardless, so the first press of play still lands on the spot.
  const armedRef = useRef<string | null>(null);
  useEffect(() => {
    if (!episode || startAt == null || !isPlayable(episode)) {
      return;
    }
    const token = `${episode.id}#${startAt}`;
    if (armedRef.current === token) {
      return;
    }
    armedRef.current = token;
    play(toPlayable(episode, titleOf(episode.feedId)), { startAt });
  }, [episode, startAt, play, titleOf]);

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
  const playable = isPlayable(episode);
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
          <div className="mc-hero__actions">
            {playable && (
              <button
                type="button"
                className="mc-btn mc-btn--accent mc-btn--lg"
                onClick={() =>
                  play(
                    toPlayable(episode, titleOf(episode.feedId)),
                    startAt == null ? undefined : { startAt },
                  )
                }
              >
                {/* Say where the button will land when a shared link asked for a moment — pressing play and
                    silently starting somewhere other than the beginning is otherwise unexplained. */}
                <Icon name="play" />{' '}
                {startAt == null ? t('player.play') : t('player.playFrom', { time: formatDuration(startAt) })}
              </button>
            )}
            {/* Shared without the current query string: a timestamp is chosen in the dialog, and nothing
                else on an episode URL is part of what is being shared. */}
            <ShareButton
              path={`/episodes/${episode.slug}`}
              title={episode.title}
              episodeSlug={episode.slug}
              className="mc-btn mc-btn--lg"
            />
          </div>
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
          <SlotRegion name="main" scope={{ type: 'episode', id: episode.slug }} scopeLabel={episode.title} />
        </div>
        <aside className="mc-detail__side">
          {/* Related is core and swappable (§6.3), not a plugin — it renders above the plugin region so a
              site with no plugins still has something in its sidebar. */}
          <RelatedEpisodes episodes={related} error={relatedError} />
          <RelatedPins slug={episode.slug} onChange={reloadRelated} />
          <SlotRegion
            name="sidebar"
            scope={{ type: 'episode', id: episode.slug }}
            scopeLabel={episode.title}
          />
        </aside>
      </div>

      {/* Sequential navigation is core and always shown, separately from anything a related-strategy
          suggests (§6.2) — cards rather than bare links, so "what comes next" is a place to go. */}
      <nav className="mc-prevnext" aria-label={t('episode.sequence')}>
        {adjacent?.prev ? (
          <Link className="mc-prevnext__link" to={`/episodes/${adjacent.prev.slug}`}>
            <span className="mc-prevnext__dir mc-muted">
              <Icon name="arrow-left" /> {t('episode.previous')}
            </span>
            <span className="mc-prevnext__title">{adjacent.prev.title}</span>
          </Link>
        ) : (
          <span />
        )}
        {adjacent?.next ? (
          <Link className="mc-prevnext__link mc-prevnext__link--next" to={`/episodes/${adjacent.next.slug}`}>
            <span className="mc-prevnext__dir mc-muted">
              {t('episode.next')} <Icon name="arrow-right" />
            </span>
            <span className="mc-prevnext__title">{adjacent.next.title}</span>
          </Link>
        ) : (
          <span />
        )}
      </nav>
    </section>
  );
}
