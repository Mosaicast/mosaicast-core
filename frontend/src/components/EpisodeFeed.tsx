// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import type { EpisodeSummary, Paged, PublicFeed } from '../api/types';
import { EpisodeCard } from './EpisodeCard';
import { FilterBar, type FilterValues } from './FilterBar';

/**
 * The unified episode feed (§6.1) — the shell's centerpiece. Lists episodes across all feeds (or one, when
 * {@code fixedFeedId} scopes it to the `/feeds/:id` page), with feed/season/order as filters whose state
 * lives in the URL (shareable, back-button-friendly). Feeds-as-filter, episodes-as-content.
 */
const PAGE_SIZE = 20;

export function EpisodeFeed({ fixedFeedId }: { fixedFeedId?: string }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();

  const showFeedFilter = !fixedFeedId;
  const feedId = fixedFeedId ?? params.get('feedId') ?? '';
  const season = params.get('season') ?? '';
  const order: FilterValues['order'] = params.get('order') === 'oldest' ? 'oldest' : 'newest';
  const page = Math.max(0, Number(params.get('page') ?? 0));
  const values: FilterValues = useMemo(() => ({ feedId, season, order }), [feedId, season, order]);

  const [feeds, setFeeds] = useState<PublicFeed[]>([]);
  const [seasons, setSeasons] = useState<number[]>([]);
  const [data, setData] = useState<Paged<EpisodeSummary> | null>(null);
  const [failed, setFailed] = useState(false);

  // Feed dropdown options (only when not already scoped to a feed).
  useEffect(() => {
    if (!showFeedFilter) {
      return;
    }
    api
      .get<PublicFeed[]>('/api/feeds')
      .then(setFeeds)
      .catch(() => setFeeds([]));
  }, [showFeedFilter]);

  // Season options depend on the selected feed (a season is defined within a feed, §4.4).
  useEffect(() => {
    if (!feedId) {
      setSeasons([]);
      return;
    }
    api
      .get<number[]>(`/api/feeds/${feedId}/seasons`)
      .then(setSeasons)
      .catch(() => setSeasons([]));
  }, [feedId]);

  // The episodes themselves.
  useEffect(() => {
    const query = new URLSearchParams({ order, page: String(page), size: String(PAGE_SIZE) });
    if (feedId) {
      query.set('feedId', feedId);
    }
    if (season) {
      query.set('season', season);
    }
    setFailed(false);
    api
      .get<Paged<EpisodeSummary>>(`/api/episodes?${query.toString()}`)
      .then(setData)
      .catch(() => setFailed(true));
  }, [feedId, season, order, page]);

  const updateFilters = (patch: Partial<FilterValues>) => {
    const next = new URLSearchParams(params);
    // A filter change resets pagination.
    next.delete('page');
    for (const [key, value] of Object.entries(patch)) {
      if (value) {
        next.set(key, value);
      } else {
        next.delete(key);
      }
    }
    // On the feed-scoped page, feedId is fixed by the route — never write it to the query.
    if (fixedFeedId) {
      next.delete('feedId');
    }
    setParams(next);
  };

  const goToPage = (p: number) => {
    const next = new URLSearchParams(params);
    next.set('page', String(p));
    setParams(next);
  };

  return (
    <div>
      <FilterBar
        values={values}
        feeds={feeds}
        seasons={seasons}
        showFeedFilter={showFeedFilter}
        onChange={updateFilters}
      />

      {failed && <p className="mc-muted">{t('feed.loadError')}</p>}
      {data && data.items.length === 0 && <p className="mc-muted">{t('feed.empty')}</p>}

      {data && data.items.length > 0 && (
        <div className="mc-card-grid">
          {data.items.map((episode) => (
            <EpisodeCard key={episode.id} episode={episode} />
          ))}
        </div>
      )}

      {data && data.totalPages > 1 && (
        <nav className="mc-pager" aria-label={t('feed.pagination')}>
          <button type="button" className="mc-btn" disabled={page <= 0} onClick={() => goToPage(page - 1)}>
            {t('feed.prevPage')}
          </button>
          <span className="mc-muted">{t('feed.pageOf', { page: page + 1, total: data.totalPages })}</span>
          <button
            type="button"
            className="mc-btn"
            disabled={page >= data.totalPages - 1}
            onClick={() => goToPage(page + 1)}
          >
            {t('feed.nextPage')}
          </button>
        </nav>
      )}
    </div>
  );
}
