// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';

import { EpisodeFeed } from '../components/EpisodeFeed';

/**
 * The home view (ARCHITECTURE §6.1): the unified episode feed across all feeds is the centerpiece, with
 * feed / season / ordering offered as filters (state in the URL). Episodes are content; feeds are a filter.
 */
export function Home() {
  const { t } = useTranslation();
  return (
    <section className="mc-page">
      <h1 className="mc-page__title">{t('home.episodes')}</h1>
      <EpisodeFeed />
    </section>
  );
}
