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
import { FilterBar, type FilterValues, type TagOption } from './FilterBar';
import { SitePanel } from './SitePanel';
import { announce } from '../a11y/LiveRegion';

/**
 * The unified episode feed (§6.1) — the shell's centerpiece. Tabs (feed scope) on top, then a two-column
 * layout: a scope **panel** (feed panel on a feed tab, site panel on All) beside the episode list. The list
 * **infinite-scrolls** (auto-loads the next page as you near the end, with a Load-more fallback). Feed is a
 * scope chosen by the tabs; **season / tag / order** are URL filters. On All each card shows its feed's
 * title; on a single feed that's omitted.
 */
const PAGE_SIZE = 20;

export function EpisodeFeed({ fixedFeedId }: { fixedFeedId?: string }) {
  // `fixedFeedId` is the feed's public slug when the view is scoped to one feed — the same value the URL
  // carries and the plugin `feed` scope is addressed by. The API resolves a UUID here too (older links).
  const { t } = useTranslation();
  const { titleOf, feeds } = useFeeds();
  const [params, setParams] = useSearchParams();

  const feedId = fixedFeedId ?? '';
  const season = params.get('season') ?? '';
  const tag = params.get('tag') ?? '';
  const order: FilterValues['order'] = params.get('order') === 'oldest' ? 'oldest' : 'newest';
  const values: FilterValues = useMemo(() => ({ season, tag, order }), [season, tag, order]);

  const [seasons, setSeasons] = useState<number[]>([]);
  const [tags, setTags] = useState<TagOption[]>([]);
  const [items, setItems] = useState<EpisodeSummary[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [matching, setMatching] = useState<number | null>(null);
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
  // The query the list currently holds. `loadMore` is fired from an IntersectionObserver and from a button,
  // so a page-2 request can still be in flight when the visitor changes a filter — and its answer belongs to
  // a list that no longer exists. Without this the old page was appended to the new one, its `totalPages`
  // overwrote the new one's, and its `finally` cleared the loading state of the request that replaced it.
  const queryBaseRef = useRef(queryBase);
  queryBaseRef.current = queryBase;

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
    api.get<TagOption[]>(`/api/tags${q}`).then(setTags).catch(() => setTags([]));
  }, [feedId]);

  // The first load of a page is announced by the page itself (its title, its heading); a later filter
  // change is not, and the list changing under a screen-reader user said nothing (core#172).
  const announcedOnce = useRef(false);
  // Through a ref: a language switch must not refetch the list, which `t` as a dependency would.
  const tRef = useRef(t);
  tRef.current = t;

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
        setMatching(res.totalElements);
        if (announcedOnce.current) {
          announce(tRef.current('feed.episodeCount', { count: res.totalElements }));
        }
        announcedOnce.current = true;
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
    const forQuery = queryBase;
    setLoading(true);
    api
      .get<Paged<EpisodeSummary>>(`/api/episodes?${queryBase}&page=${next}`)
      .then((res) => {
        if (queryBaseRef.current !== forQuery) return;
        setItems((prev) => [...prev, ...res.items]);
        setPage(next);
        setTotalPages(res.totalPages);
      })
      .catch(() => {
        if (queryBaseRef.current === forQuery) setFailed(true);
      })
      .finally(() => {
        if (queryBaseRef.current === forQuery) setLoading(false);
      });
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

  // Everything in scope, unfiltered: this feed's count, or every feed's on the site list.
  const total = fixedFeedId
    ? (feeds.find((feed) => feed.slug === fixedFeedId || feed.id === fixedFeedId)?.episodeCount ?? null)
    : feeds.length > 0
      ? feeds.reduce((sum, feed) => sum + feed.episodeCount, 0)
      : null;

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
        {fixedFeedId ? <FeedPanel feedSlug={fixedFeedId} /> : <SitePanel />}

        <div className="mc-feedmain">
          <FilterBar values={values} seasons={seasons} tags={tags} onChange={updateFilters} />
          {/* Only while something narrows the list: the panel beside it said "6 episodes" while four cards
              showed, and clearing meant resetting each dropdown by hand (#199). The order sorts; it does
              not filter, so it is left alone. */}
          {(season || tag) && (
            <p className="mc-filtered">
              <span className="mc-muted">
                {matching == null
                  ? null
                  : total == null
                    ? t('feed.episodeCount', { count: matching })
                    : t('feed.filteredCount', { count: matching, total })}
              </span>
              <button type="button" className="mc-btn mc-btn--sm" onClick={() => updateFilters({ season: '', tag: '' })}>
                {t('feed.clearFilters')}
              </button>
            </p>
          )}

          {failed && items.length === 0 && (
            <div className="mc-empty">
              <p className="mc-empty__title">{t('feed.loadError')}</p>
            </div>
          )}
          {!loading && !failed && items.length === 0 && (
            <div className="mc-empty">
              <p className="mc-empty__title">{t('feed.empty')}</p>
              <p>{t('feed.emptyHint')}</p>
            </div>
          )}

          {/* First page only: placeholders in the shape of the cards, so nothing jumps when they arrive.
              Later pages append below what is already readable and need no placeholder. */}
          {loading && items.length === 0 && (
            <div className="mc-card-grid" aria-hidden="true">
              {[0, 1, 2].map((n) => (
                <div className="mc-skeleton" key={n}>
                  <div className="mc-skeleton__cover" />
                  <div className="mc-skeleton__body">
                    <div className="mc-skeleton__line mc-skeleton__line--short" />
                    <div className="mc-skeleton__line mc-skeleton__line--title" />
                    <div className="mc-skeleton__line" />
                    <div className="mc-skeleton__line" />
                  </div>
                </div>
              ))}
            </div>
          )}

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
          {loading && items.length > 0 && (
            <p className="mc-muted mc-feed-loading">{t('common.loading')}</p>
          )}
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
