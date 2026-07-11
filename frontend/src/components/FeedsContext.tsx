// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api } from '../api/client';
import type { PublicFeed } from '../api/types';

/**
 * Loads the public feed catalog once (`GET /api/feeds`) and shares it, so the tabs, the single-feed
 * redirect, and the cards' feed-title lookup don't each re-fetch it.
 */
interface FeedsValue {
  feeds: PublicFeed[];
  loaded: boolean;
  titleOf: (feedId: string) => string | undefined;
}

const FeedsContext = createContext<FeedsValue>({ feeds: [], loaded: false, titleOf: () => undefined });

export function useFeeds(): FeedsValue {
  return useContext(FeedsContext);
}

export function FeedsProvider({ children }: { children: ReactNode }) {
  const [feeds, setFeeds] = useState<PublicFeed[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    api
      .get<PublicFeed[]>('/api/feeds')
      .then((data) => active && setFeeds(data))
      .catch(() => active && setFeeds([]))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, []);

  const value = useMemo<FeedsValue>(() => {
    const byId = new Map(feeds.map((f) => [f.id, f.title]));
    return { feeds, loaded, titleOf: (id) => byId.get(id) };
  }, [feeds, loaded]);

  return <FeedsContext.Provider value={value}>{children}</FeedsContext.Provider>;
}
