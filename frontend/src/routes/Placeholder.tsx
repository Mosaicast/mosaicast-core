// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';

import type { EpisodeSummary } from '../api/types';
import { RelatedEpisodes } from '../components/RelatedEpisodes';
import { useResource } from '../hooks/useResource';

/**
 * Real HTTP-style 404 landmark for unknown routes (ARCHITECTURE §6.6 hygiene).
 *
 * **A way out, not just a statement.** It used to be two lines — "Not found. That page does not exist." —
 * with no link home, no search and nothing to do next, which for a mistyped or expired link is the end of
 * the visit (core#179). The three things offered here are the three things a person actually wants: go
 * back to the front page, look for what they came for, or see what is recent.
 *
 * The status code is the server's: the SPA fallback answers 404 for a path the router does not claim, so
 * this page and the response agree.
 */
export function NotFound() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [draft, setDraft] = useState('');
  const { data: recent, error } = useResource<{ items: EpisodeSummary[] }>(
    '/api/episodes?order=newest&size=3&page=0',
  );

  return (
    <section className="mc-page mc-notfound">
      <h1 className="mc-page__title">{t('notFound.title')}</h1>
      <p className="mc-muted">{t('notFound.body')}</p>

      <form
        className="mc-search__form"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          const query = draft.trim();
          if (query) {
            navigate(`/search?q=${encodeURIComponent(query)}`);
          }
        }}
      >
        <label className="mc-search__field">
          <span className="mc-sr-only">{t('search.label')}</span>
          <input
            className="mc-input"
            type="search"
            name="q"
            value={draft}
            placeholder={t('search.placeholder')}
            onChange={(event) => setDraft(event.target.value)}
          />
        </label>
        <button className="mc-btn" type="submit">
          {t('search.submit')}
        </button>
      </form>

      <p>
        <Link to="/">{t('notFound.home')}</Link>
      </p>

      {/* Cheap and useful: whoever landed here came for this podcast, and the newest episodes are the
          closest thing to what they were looking for. */}
      <RelatedEpisodes episodes={recent?.items ?? null} error={error} title={t('notFound.recent')} />
    </section>
  );
}
