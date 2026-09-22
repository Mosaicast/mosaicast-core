// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import type { EpisodeSummary, Paged } from '../api/types';
import { useUser } from '../auth/UserContext';

/**
 * Curating pinned related episodes (ARCHITECTURE §6.3), inline on the episode being curated.
 *
 * **Why here and not in the admin area.** A pin is a judgement about *this* episode — "the callback in this
 * one lands better if you have heard that one" — and it is made while looking at it. An Admin → Episodes
 * screen would mean picking the episode from a list first, which is the same decision made with less
 * information in front of you. The admin area gets the settings that are settings; this is editorial.
 *
 * Visible to PODCASTER and ADMIN only. The server enforces that too (`/api/admin/episodes/**`); this only
 * decides whether to render the controls.
 */
export function RelatedPins({ slug, onChange }: { slug: string; onChange: () => void }) {
  const { t } = useTranslation();
  const { user } = useUser();

  const [pins, setPins] = useState<EpisodeSummary[]>([]);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<EpisodeSummary[]>([]);
  const [open, setOpen] = useState(false);
  const [failed, setFailed] = useState(false);

  const mayCurate = user?.role === 'podcaster' || user?.role === 'admin';

  const load = useCallback(async () => {
    if (!mayCurate) {
      return;
    }
    try {
      setPins(await api.get<EpisodeSummary[]>(`/api/admin/episodes/${slug}/pins`));
    } catch {
      setFailed(true);
    }
  }, [slug, mayCurate]);

  useEffect(() => {
    void load();
  }, [load]);

  // Search only once there is something to search for; an empty query would return the whole catalogue.
  useEffect(() => {
    if (!open || query.trim().length < 2) {
      setResults([]);
      return;
    }
    let active = true;
    const timer = setTimeout(() => {
      api
        .get<Paged<EpisodeSummary>>(`/api/episodes/search?q=${encodeURIComponent(query.trim())}&size=8`)
        .then((page) => active && setResults(page.items.filter((item) => item.slug !== slug)))
        .catch(() => active && setResults([]));
    }, 250);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [query, open, slug]);

  const mutate = async (run: () => Promise<EpisodeSummary[]>) => {
    try {
      setPins(await run());
      // The public list is computed from these, so it has to be re-read rather than guessed at.
      onChange();
    } catch {
      setFailed(true);
    }
  };

  const pin = (relatedSlug: string) => {
    setQuery('');
    return mutate(() =>
      api.post<EpisodeSummary[]>(`/api/admin/episodes/${slug}/pins`, { relatedSlug }),
    );
  };
  const unpin = (relatedSlug: string) =>
    mutate(() => api.del<EpisodeSummary[]>(`/api/admin/episodes/${slug}/pins/${relatedSlug}`));

  if (!mayCurate) {
    return null;
  }

  return (
    <section className="mc-pins">
      <h2 className="mc-pins__heading">{t('episode.pins')}</h2>
      <p className="mc-muted mc-pins__hint">{t('episode.pinsHint')}</p>

      {pins.length > 0 && (
        <ul className="mc-pins__list">
          {pins.map((episode) => (
            <li key={episode.id} className="mc-pins__item">
              <span className="mc-pins__title">{episode.title}</span>
              <button
                type="button"
                className="mc-btn mc-btn--sm"
                onClick={() => void unpin(episode.slug)}
                aria-label={t('episode.unpinOne', { title: episode.title })}
              >
                {t('episode.unpin')}
              </button>
            </li>
          ))}
        </ul>
      )}

      {open ? (
        <div className="mc-pins__search">
          <input
            className="mc-input"
            type="search"
            // The field appears because the visitor just pressed the button that opens it, so focus is
            // following their action rather than being taken from them.
            // eslint-disable-next-line jsx-a11y/no-autofocus
            autoFocus
            value={query}
            placeholder={t('episode.pinSearch')}
            onChange={(e) => setQuery(e.target.value)}
          />
          {results.length > 0 && (
            <ul className="mc-pins__results">
              {results.map((episode) => (
                <li key={episode.id}>
                  <button type="button" className="mc-pins__result" onClick={() => void pin(episode.slug)}>
                    {episode.title}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <button type="button" className="mc-btn mc-btn--sm" onClick={() => setOpen(false)}>
            {t('episode.pinCancel')}
          </button>
        </div>
      ) : (
        <button type="button" className="mc-btn mc-btn--sm" onClick={() => setOpen(true)}>
          {t('episode.pinAdd')}
        </button>
      )}

      {failed && <p className="mc-muted">{t('episode.pinFailed')}</p>}
    </section>
  );
}
