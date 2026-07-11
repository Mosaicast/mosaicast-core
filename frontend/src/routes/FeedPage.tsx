// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { api } from '../api/client';
import type { PublicFeed } from '../api/types';
import { EpisodeFeed } from '../components/EpisodeFeed';

/**
 * A single feed's page (`/feeds/:id`): the same unified episode feed, scoped to one feed (the feed filter
 * is pre-applied and hidden). A single-feed site therefore looks like the mockup — episodes + season/order.
 */
export function FeedPage() {
  const { feedId = '' } = useParams();
  const [title, setTitle] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<PublicFeed[]>('/api/feeds')
      .then((feeds) => setTitle(feeds.find((f) => f.id === feedId)?.title ?? null))
      .catch(() => setTitle(null));
  }, [feedId]);

  return (
    <section className="mc-page">
      {title && <h1 className="mc-page__title">{title}</h1>}
      <EpisodeFeed fixedFeedId={feedId} />
    </section>
  );
}
