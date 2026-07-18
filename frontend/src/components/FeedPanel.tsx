// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import DOMPurify from 'dompurify';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import type { FeedDetail } from '../api/types';
import { Cover } from './Cover';
import { SlotRegion } from './SlotRegion';

/**
 * The feed-scope panel (ARCHITECTURE §6.1, mockup left column): the show cover, title, author, description
 * and episode count for the active feed, plus the **feed** plugin slot region ("Space for Plugins", filled
 * by feed-scoped plugins in E5). Fetches `GET /api/feeds/{id}`.
 */
export function FeedPanel({ feedId }: { feedId: string }) {
  const { t } = useTranslation();
  const [feed, setFeed] = useState<FeedDetail | null>(null);

  useEffect(() => {
    let active = true;
    api
      .get<FeedDetail>(`/api/feeds/${feedId}`)
      .then((data) => active && setFeed(data))
      .catch(() => active && setFeed(null));
    return () => {
      active = false;
    };
  }, [feedId]);

  const description = feed?.description ? DOMPurify.sanitize(feed.description) : '';

  return (
    <aside className="mc-scope-panel">
      <Cover id={feedId} imageUrl={feed?.imageUrl ?? null} size={220} />
      <h1 className="mc-scope-panel__title">{feed?.title ?? ''}</h1>
      {feed?.author && <p className="mc-scope-panel__author mc-muted">{t('card.by', { author: feed.author })}</p>}
      {feed && (
        <p className="mc-scope-panel__count mc-muted">{t('feed.episodeCount', { count: feed.episodeCount })}</p>
      )}
      {description && (
        <div className="mc-scope-panel__desc mc-muted" dangerouslySetInnerHTML={{ __html: description }} />
      )}
      {/* Feed-scoped plugins (E5) mount here. */}
      <SlotRegion name="feed" scope={{ type: 'feed', id: feedId }} />
      <SlotRegion name="sidebar" scope={{ type: 'feed', id: feedId }} />
    </aside>
  );
}
