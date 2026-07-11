// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useParams } from 'react-router-dom';

import { EpisodeFeed } from '../components/EpisodeFeed';

/**
 * A single feed's page (`/feeds/:id`) — the active feed tab. The same episode feed scoped to one feed; the
 * feed panel (cover/title/meta/plugins) is the heading. On a single-feed site this is the whole site (Home
 * redirects here).
 */
export function FeedPage() {
  const { feedId = '' } = useParams();
  return (
    <section className="mc-page">
      <EpisodeFeed fixedFeedId={feedId} />
    </section>
  );
}
