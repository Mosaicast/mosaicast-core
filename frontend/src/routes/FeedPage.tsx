// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useParams } from 'react-router-dom';

import { EpisodeFeed } from '../components/EpisodeFeed';

/**
 * A single feed's page (`/feeds/:feedSlug`) — the active feed tab. The same episode feed scoped to one feed;
 * the feed panel (cover/title/meta/plugins) is the heading. On a single-feed site this is the whole site
 * (Home redirects here).
 *
 * The route parameter is the feed's **public slug**, as episodes have used since `0.5.2`. The API resolves a
 * UUID here too, so a link shared before slugs existed still lands on the right feed.
 */
export function FeedPage() {
  const { feedSlug = '' } = useParams();
  return (
    <section className="mc-page">
      <EpisodeFeed fixedFeedId={feedSlug} />
    </section>
  );
}
