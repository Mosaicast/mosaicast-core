// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import type { EpisodeSummary, Paged } from '../api/types';
import { EpisodeCard } from './EpisodeCard';
import { FeedPanel } from './FeedPanel';
import { FeedTabs } from './FeedTabs';
import { useFeeds } from './FeedsContext';
import { FilterBar, type FilterValues } from './FilterBar';
import { SitePanel } from './SitePanel';

/**
 * The unified episode feed (§6.1) — the shell's centerpiece. Tabs (feed scope) on top, then a two-column
 * layout: a scope **panel** (feed panel on a feed tab, site panel on All) beside the episode list. The list
 * **infinite-scrolls** (auto-loads the next page as you near the end, with a Load-more fallback). Feed is a
 * scope chosen by the tabs; **season / tag / order** are URL filters. On All each card shows its feed's
 * title; on a single feed that's omitted.
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
  const values: FilterValues = useMemo(() => ({ season, tag, order }), [season, tag, order]);

  const [seasons, setSeasons] = useState<number[]>([]);
  const [tags, setTags] = useState<string[]>([]);
  const [items, setItems] = useState<EpisodeSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  // The query for the current scope/filters (everything but page/size). Changing it resets the list.
  const queryBase = useMemo(() => {
    const q = new URLSearchParams({ order, size: String(PAGE_SIZE) });
    if (feedId) q.set('feedId', feedId);
    if (season) q.set('season', season);
    if (tag) q.set('tag', tag);
    return q.toString();
  }, [feedId, season, tag, order]);

  // Season options (defined within a feed, §4.4) — only when scoped to one.
  useEffect(() => {
    if (!feedId) {
      setSeasons([]);
      return;
    }
    api.get<number[]>(`/api/feeds/${feedId}/seasons`).then(setSeasons).catch(() => setSeasons([]));
  }, [feedId]);

  // Tag options for the current scope.
  useEffect(() => {
    const q = feedId ? `?feedId=${feedId}` : '';
    api.get<string[]>(`/api/tags${q}`).then(setTags).catch(() => setTags([]));
  }, [feedId]);

  // Reset + load the first page whenever the scope/filters change.
  useEffect(() => {
    let active = true;
    setItems([]);
    setPage(0);
    setTotalPages(0);
    setFailed(false);
    setLoading(true);
    api
      .get<Paged<EpisodeSummary>>(`/api/episodes?${queryBase}&page=0`)
      .then((res) => {
        if (!active) return;
        setItems(res.items);
        setTotalPages(res.totalPages);
      })
      .catch(() => active && setFailed(true))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [queryBase]);

  const hasMore = page < totalPages - 1;

  const loadMore = useCallback(() => {
    if (loading || page >= totalPages - 1) return;
    const next = page + 1;
    setLoading(true);
    api
      .get<Paged<EpisodeSummary>>(`/api/episodes?${queryBase}&page=${next}`)
      .then((res) => {
        setItems((prev) => [...prev, ...res.items]);
        setPage(next);
        setTotalPages(res.totalPages);
      })
      .catch(() => setFailed(true))
      .finally(() => setLoading(false));
  }, [loading, page, totalPages, queryBase]);

  // Auto-load as the sentinel scrolls into view.
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting) loadMore();
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [loadMore]);

  const updateFilters = (patch: Partial<FilterValues>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(patch)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    setParams(next);
  };

  return (
    <div>
      <FeedTabs />
      <div className="mc-feedlayout">
        {fixedFeedId ? <FeedPanel feedId={fixedFeedId} /> : <SitePanel />}

        <div className="mc-feedmain">
          <FilterBar values={values} seasons={seasons} tags={tags} onChange={updateFilters} />

          {failed && items.length === 0 && <p className="mc-muted">{t('feed.loadError')}</p>}
          {!loading && !failed && items.length === 0 && <p className="mc-muted">{t('feed.empty')}</p>}

          <div className="mc-card-grid">
            {items.map((episode) => (
              <EpisodeCard
                key={episode.id}
                episode={episode}
                feedTitle={fixedFeedId ? undefined : titleOf(episode.feedId)}
              />
            ))}
          </div>

          {/* Sentinel for auto-load + a keyboard/no-JS fallback. */}
          <div ref={sentinelRef} className="mc-feed-sentinel" aria-hidden="true" />
          {loading && <p className="mc-muted mc-feed-loading">{t('common.loading')}</p>}
          {hasMore && !loading && (
            <div className="mc-feed-more">
              <button type="button" className="mc-btn" onClick={loadMore}>
                {t('feed.loadMore')}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
