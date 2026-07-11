// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';

import { EpisodeFeed } from '../components/EpisodeFeed';
import { useFeeds } from '../components/FeedsContext';

/**
 * The home view (§6.1): the unified episode feed across all feeds (the "All" tab). When the site has exactly
 * **one** feed there are no tabs — Home canonicalizes to that feed's own URL (`/feeds/:id`) so a bookmark
 * stays correct even if more feeds are added later.
 */
export function Home() {
  const { t } = useTranslation();
  const { feeds, loaded } = useFeeds();

  if (loaded && feeds.length === 1) {
    return <Navigate to={`/feeds/${feeds[0].id}`} replace />;
  }

  return (
    <section className="mc-page">
      <h1 className="mc-page__title">{t('home.episodes')}</h1>
      <EpisodeFeed />
    </section>
  );
}
