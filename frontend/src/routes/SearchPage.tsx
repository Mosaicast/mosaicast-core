// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';

import { api } from '../api/client';
import type { EpisodeSummary } from '../api/types';
import { EpisodeCard } from '../components/EpisodeCard';
import { Icon } from '../components/Icon';

/** One plugin's section of the answer, as `/api/search` returns it. */
interface PluginSection {
  pluginId: string;
  name: string;
  hits: { href: string; title: string; snippet: string }[];
  timedOut: boolean;
}

interface SearchResults {
  query: string;
  episodes: EpisodeSummary[];
  /** Zero-based, as the API counts. */
  page: number;
  totalPages: number;
  totalElements: number;
  plugins: PluginSection[];
}

/**
 * Site-wide search (ARCHITECTURE §6): episodes, plus what plugins say about their own content.
 *
 * **Sections, not one ranked list.** A plugin's score and Postgres `ts_rank` are not on one scale, so the
 * host groups by source rather than interleaving — see the SDK's `SearchProvider`. The visitor loses a
 * single ordering and gains an answer that stays honest when a plugin changes how it ranks.
 *
 * The query lives in the URL (`?q=`), so a search is linkable and the back button behaves.
 */
export function SearchPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const query = params.get('q') ?? '';

  // Kept apart from the URL so typing does not push a history entry per keystroke; the URL is written on
  // submit, and the URL is what the request follows.
  const [draft, setDraft] = useState(query);
  const [results, setResults] = useState<SearchResults | null>(null);
  // Accumulated across pages, like the feed list: a visitor reading results should not lose the ones they
  // have already read to see the next twenty.
  const [episodes, setEpisodes] = useState<EpisodeSummary[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => setDraft(query), [query]);

  // A new query starts again from the first page.
  useEffect(() => {
    setPage(0);
    setEpisodes([]);
  }, [query]);

  useEffect(() => {
    if (!query.trim()) {
      setResults(null);
      setEpisodes([]);
      return;
    }
    let active = true;
    setLoading(true);
    setFailed(false);
    api
      .get<SearchResults>(`/api/search?q=${encodeURIComponent(query)}&page=${page}`)
      .then((res) => {
        if (!active) return;
        setResults(res);
        // Appended for a later page, replaced for the first — which is also what a re-submitted query is.
        setEpisodes((current) => (res.page === 0 ? res.episodes : [...current, ...res.episodes]));
      })
      .catch(() => active && setFailed(true))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [query, page]);

  const empty =
    results != null && episodes.length === 0 && results.plugins.every((p) => p.hits.length === 0);
  const hasMore = results != null && results.page < results.totalPages - 1;

  return (
    <section className="mc-page mc-search">
      <h1 className="mc-search__heading">{t('search.title')}</h1>

      <form
        className="mc-search__form"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setParams(draft.trim() ? { q: draft.trim() } : {});
        }}
      >
        <label className="mc-search__field">
          <span className="mc-sr-only">{t('search.label')}</span>
          <input
            className="mc-input"
            type="search"
            name="q"
            value={draft}
            // The search page exists to be typed into; arriving with the caret anywhere else is the
            // surprising version.
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            placeholder={t('search.placeholder')}
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        <button className="mc-btn" type="submit">
          {t('search.submit')}
        </button>
      </form>

      {loading && <p className="mc-muted">{t('common.loading')}</p>}
      {failed && <p className="mc-error">{t('search.failed')}</p>}
      {!query.trim() && !loading && <p className="mc-muted">{t('search.hint')}</p>}
      {empty && !loading && <p className="mc-muted">{t('search.none', { query: results?.query })}</p>}

      {results != null && episodes.length > 0 && (
        <section className="mc-search__group">
          {/*
            The count, because twenty results and the first twenty of hundreds looked identical: search was
            the only list on the site that silently truncated, with no total and no way to ask for more
            (core#178). `role="status"` so the number is announced when it changes.
          */}
          <h2 className="mc-search__group-heading" role="status">
            {t('search.episodes')}{' '}
            <span className="mc-muted">
              {t('search.count', { shown: episodes.length, total: results.totalElements })}
            </span>
          </h2>
          <div className="mc-feed">
            {episodes.map((episode) => (
              <EpisodeCard key={episode.id} episode={episode} />
            ))}
          </div>
          {hasMore && !loading && (
            <div className="mc-feed-more">
              <button type="button" className="mc-btn" onClick={() => setPage((current) => current + 1)}>
                {t('feed.loadMore')}
              </button>
            </div>
          )}
        </section>
      )}

      {results?.plugins.map((section) => (
        <section className="mc-search__group" key={section.pluginId}>
          <h2 className="mc-search__group-heading">{section.name}</h2>
          {/*
            A section that ran out of its budget says so. "Found nothing" and "did not answer" are
            different answers, and a visitor given the first when the second is true concludes the content
            is not on this site.
          */}
          {section.timedOut ? (
            <p className="mc-muted">{t('search.timedOut')}</p>
          ) : (
            <ul className="mc-search__hits">
              {section.hits.map((hit) => (
                <li key={hit.href}>
                  <Link className="mc-search__hit" to={hit.href}>
                    <span className="mc-search__hit-title">{hit.title}</span>
                    {hit.snippet && <span className="mc-search__hit-snippet mc-muted">{hit.snippet}</span>}
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </section>
  );
}

/** The top bar's way in: an icon that is the whole control, so it needs the label (§12.3). */
export function SearchLink() {
  const { t } = useTranslation();
  return (
    <Link className="mc-btn mc-btn--ghost mc-search__open" to="/search">
      <Icon name="search" label={t('search.title')} />
    </Link>
  );
}
