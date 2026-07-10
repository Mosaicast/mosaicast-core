// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { api } from '../api/client';
import type { PublicFeed } from '../api/types';

/**
 * The home / browse view (ARCHITECTURE §6.1). E4a lists the public feed catalog (`GET /api/feeds`) as a
 * simple index proving the API client + theme end-to-end; the wide episode cards, filter bar and the
 * site-scope recent list land in E4b.
 */
export function Home() {
  const { t } = useTranslation();
  const [feeds, setFeeds] = useState<PublicFeed[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    api
      .get<PublicFeed[]>('/api/feeds')
      .then((data) => active && setFeeds(data))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, []);

  return (
    <section className="mc-page">
      <h1 className="mc-page__title">{t('home.feeds')}</h1>
      {failed && <p className="mc-muted">{t('home.loadError')}</p>}
      {feeds && feeds.length === 0 && <p className="mc-muted">{t('home.empty')}</p>}
      {feeds && feeds.length > 0 && (
        <ul className="mc-feed-list">
          {feeds.map((feed) => (
            <li key={feed.id} className="mc-feed-list__item">
              <Link to={`/feeds/${feed.id}`}>{feed.title}</Link>
              <span className="mc-muted">{t('home.episodeCount', { count: feed.episodeCount })}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
