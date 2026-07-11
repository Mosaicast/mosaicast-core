// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import type { EpisodeSummary, Paged } from '../api/types';
import { EpisodeCard } from './EpisodeCard';
import { FeedTabs } from './FeedTabs';
import { useFeeds } from './FeedsContext';
import { FilterBar, type FilterValues } from './FilterBar';

/**
 * The unified episode feed (§6.1) — the shell's centerpiece. Episodes are the content; the **feed** is a
 * scope chosen by the tabs (All, or `/feeds/:id` → {@code fixedFeedId}), and **season / tag / order** are
 * filters whose state lives in the URL. On the All view each card shows its feed's title; on a single feed
 * that's redundant and omitted.
 */
const PAGE_SIZE = 20;

export function EpisodeFeed({ fixedFeedId }: { fixedFeedId?: string }) {
  const { t } = useTranslation();
  const { titleOf } = useFeeds();
  const [params, setParams] = useSearchParams();

  const feedId = fixedFeedId ?? '';
  const season = params.get('season') ?? '';
  const tag = params.get('tag') ?? '';
  const order: FilterValues['order'] = params.get('order') === 'oldest' ? 'oldest' : 'newest';
  const page = Math.max(0, Number(params.get('page') ?? 0));
  const values: FilterValues = useMemo(() => ({ season, tag, order }), [season, tag, order]);

  const [seasons, setSeasons] = useState<number[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [data, setData] = useState<Paged<EpisodeSummary> | null>(null);
  const [failed, setFailed] = useState(false);

  // Season options (defined within a feed, §4.4) — only when scoped to one.
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

  // Tag options for the current scope (all feeds, or the active one).
  useEffect(() => {
    const q = feedId ? `?feedId=${feedId}` : '';
    api
      .get<string[]>(`/api/tags${q}`)
      .then(setTags)
      .catch(() => setTags([]));
  }, [feedId]);

  // The episodes.
  useEffect(() => {
    const query = new URLSearchParams({ order, page: String(page), size: String(PAGE_SIZE) });
    if (feedId) {
      query.set('feedId', feedId);
    }
    if (season) {
      query.set('season', season);
    }
    if (tag) {
      query.set('tag', tag);
    }
    setFailed(false);
    api
      .get<Paged<EpisodeSummary>>(`/api/episodes?${query.toString()}`)
      .then(setData)
      .catch(() => setFailed(true));
  }, [feedId, season, tag, order, page]);

  const updateFilters = (patch: Partial<FilterValues>) => {
    const next = new URLSearchParams(params);
    next.delete('page'); // a filter change resets pagination
    for (const [key, value] of Object.entries(patch)) {
      if (value) {
        next.set(key, value);
      } else {
        next.delete(key);
      }
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
      <FeedTabs />
      <FilterBar values={values} seasons={seasons} tags={tags} onChange={updateFilters} />

      {failed && <p className="mc-muted">{t('feed.loadError')}</p>}
      {data && data.items.length === 0 && <p className="mc-muted">{t('feed.empty')}</p>}

      {data && data.items.length > 0 && (
        <div className="mc-card-grid">
          {data.items.map((episode) => (
            <EpisodeCard
              key={episode.id}
              episode={episode}
              feedTitle={fixedFeedId ? undefined : titleOf(episode.feedId)}
            />
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
