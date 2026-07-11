// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import type { PublicFeed } from '../api/types';

/**
 * The feed filter bar (§6.1): the axes that narrow the unified episode feed — which podcast (feed), season,
 * and ordering. Presentational; the owning view keeps the state in the URL so views are shareable. The feed
 * dropdown is hidden when the view is already scoped to one feed (the `/feeds/:id` page). Season is offered
 * only once a feed is chosen (a season is defined within a feed, §4.4).
 */
export interface FilterValues {
  feedId: string;
  season: string;
  order: 'newest' | 'oldest';
}

interface FilterBarProps {
  values: FilterValues;
  feeds: PublicFeed[];
  seasons: number[];
  showFeedFilter: boolean;
  onChange: (patch: Partial<FilterValues>) => void;
}

export function FilterBar({ values, feeds, seasons, showFeedFilter, onChange }: FilterBarProps) {
  const { t } = useTranslation();

  return (
    <div className="mc-filterbar" role="group" aria-label={t('filter.label')}>
      {showFeedFilter && (
        <label className="mc-filter">
          <span className="mc-muted">{t('filter.feed')}</span>
          <select
            value={values.feedId}
            onChange={(e) => onChange({ feedId: e.target.value, season: '' })}
          >
            <option value="">{t('filter.allFeeds')}</option>
            {feeds.map((feed) => (
              <option key={feed.id} value={feed.id}>
                {feed.title}
              </option>
            ))}
          </select>
        </label>
      )}

      {seasons.length > 0 && (
        <label className="mc-filter">
          <span className="mc-muted">{t('filter.season')}</span>
          <select value={values.season} onChange={(e) => onChange({ season: e.target.value })}>
            <option value="">{t('filter.allSeasons')}</option>
            {seasons.map((s) => (
              <option key={s} value={String(s)}>
                {t('filter.seasonN', { n: s })}
              </option>
            ))}
          </select>
        </label>
      )}

      <label className="mc-filter">
        <span className="mc-muted">{t('filter.order')}</span>
        <select
          value={values.order}
          onChange={(e) => onChange({ order: e.target.value as FilterValues['order'] })}
        >
          <option value="newest">{t('filter.newest')}</option>
          <option value="oldest">{t('filter.oldest')}</option>
        </select>
      </label>
    </div>
  );
}
