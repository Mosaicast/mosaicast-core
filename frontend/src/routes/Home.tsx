// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Navigate } from 'react-router-dom';

import { useDocumentTitle } from '../a11y/documentTitle';
import { EpisodeFeed } from '../components/EpisodeFeed';
import { useFeeds } from '../components/FeedsContext';

/**
 * The home view (§6.1): the "All" tab — the unified episode feed across all feeds, with the site panel. When
 * the site has exactly **one** feed there are no tabs; Home canonicalizes to that feed's own URL
 * (`/feeds/:id`) so a bookmark stays correct even if more feeds are added later.
 */
export function Home() {
  const { feeds, loaded } = useFeeds();
  useDocumentTitle(null);

  if (loaded && feeds.length === 1) {
    return <Navigate to={`/feeds/${feeds[0].slug}`} replace />;
  }
  // Nothing until the feed list is in: rendering the unified feed straight away started its page-0 query,
  // tags and plugin fan-out — and on a single-feed site, the default, the redirect above then threw all of it
  // away and the feed page asked again (core#195). `loaded` settles on failure too, so this cannot hang.
  if (!loaded) {
    return <section className="mc-page" aria-busy="true" />;
  }

  return (
    <section className="mc-page">
      <EpisodeFeed />
    </section>
  );
}
