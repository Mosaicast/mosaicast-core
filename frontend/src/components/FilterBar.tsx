// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * The filter bar (§6.1): the axes that narrow the episode list within the active feed tab — season, tag,
 * and ordering. The feed axis is the tabs (§6.1); it is not here. Presentational; the owning view keeps the
 * state in the URL. Season is offered only once a feed is active (a season is defined within a feed, §4.4);
 * tags appear when the current scope has any.
 *
 * Seasons are listed in whichever direction the episodes are sorted, so the two controls never contradict
 * each other — with newest first, season 5 sits at the top and season 1 at the bottom.
 */
export interface FilterValues {
  season: string;
  tag: string;
  order: 'newest' | 'oldest';
}

interface FilterBarProps {
  values: FilterValues;
  seasons: number[];
  tags: string[];
  onChange: (patch: Partial<FilterValues>) => void;
}

export function FilterBar({ values, seasons, tags, onChange }: FilterBarProps) {
  const { t } = useTranslation();

  // The dropdown reads in the same direction as the list it filters. Newest-first — the default — puts the
  // current season at the top, next to "All", which is where someone reaches for it; the API keeps returning
  // seasons in canonical ascending order, because that is a property of the feed rather than of this view.
  const ordered = useMemo(
    () => (values.order === 'oldest' ? [...seasons].sort((a, b) => a - b) : [...seasons].sort((a, b) => b - a)),
    [seasons, values.order],
  );

  return (
    <div className="mc-filterbar" role="group" aria-label={t('filter.label')}>
      {seasons.length > 0 && (
        <label className="mc-filter">
          <span className="mc-muted">{t('filter.season')}</span>
          <select value={values.season} onChange={(e) => onChange({ season: e.target.value })}>
            <option value="">{t('filter.allSeasons')}</option>
            {ordered.map((s) => (
              <option key={s} value={String(s)}>
                {t('filter.seasonN', { n: s })}
              </option>
            ))}
          </select>
        </label>
      )}

      {tags.length > 0 && (
        <label className="mc-filter">
          <span className="mc-muted">{t('filter.tag')}</span>
          <select value={values.tag} onChange={(e) => onChange({ tag: e.target.value })}>
            <option value="">{t('filter.allTags')}</option>
            {tags.map((tag) => (
              <option key={tag} value={tag}>
                {tag}
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
