// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';

import { useFeeds } from './FeedsContext';

/**
 * Per-feed navigation tabs (§6.1): **All** + one tab per feed, as route links (`/` and `/feeds/:id`), so
 * the active tab is just the current URL and every tab is bookmarkable. With a **single feed there are no
 * tabs** (the site collapses to that one feed) — see the Home redirect. Episodes stay the content; the feed
 * is a scope selector.
 */
export function FeedTabs() {
  const { t } = useTranslation();
  const { feeds } = useFeeds();

  if (feeds.length <= 1) {
    return null;
  }

  return (
    <nav className="mc-tabs" aria-label={t('tabs.label')}>
      <NavLink to="/" end className={({ isActive }) => `mc-tab${isActive ? ' mc-tab--active' : ''}`}>
        {t('tabs.all')}
      </NavLink>
      {feeds.map((feed) => (
        <NavLink
          key={feed.id}
          to={`/feeds/${feed.id}`}
          className={({ isActive }) => `mc-tab${isActive ? ' mc-tab--active' : ''}`}
        >
          {feed.title}
        </NavLink>
      ))}
    </nav>
  );
}
