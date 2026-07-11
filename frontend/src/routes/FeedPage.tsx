// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useParams } from 'react-router-dom';

import { EpisodeFeed } from '../components/EpisodeFeed';
import { useFeeds } from '../components/FeedsContext';

/**
 * A single feed's page (`/feeds/:id`) — the active feed tab. The same episode feed scoped to one feed; on a
 * single-feed site this is the whole site (Home redirects here). The heading is the feed title.
 */
export function FeedPage() {
  const { feedId = '' } = useParams();
  const { titleOf } = useFeeds();
  const title = titleOf(feedId);

  return (
    <section className="mc-page">
      {title && <h1 className="mc-page__title">{title}</h1>}
      <EpisodeFeed fixedFeedId={feedId} />
    </section>
  );
}
