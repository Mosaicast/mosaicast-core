// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import type { EpisodeSummary } from '../api/types';
import { Cover } from './Cover';

/**
 * Related episodes for the detail sidebar (ARCHITECTURE §6.3).
 *
 * **Not sequential navigation.** Previous/next is core, always shown, and lives in its own landmark at the
 * foot of the page (§6.2). This is "what else is like this", it is allowed to be empty, and when it is the
 * component renders nothing at all — a heading over an empty list tells a reader the site is broken, when
 * really it just has nothing to suggest.
 *
 * The list is fetched by the page rather than here, because pinning (`RelatedPins`) changes it and the two
 * components have to agree on when to re-read.
 */
export function RelatedEpisodes({ episodes, error }: { episodes: EpisodeSummary[] | null; error: unknown }) {
  const { t } = useTranslation();

  // Nothing to suggest, or the request failed: stay silent. This is a sidebar extra, and a failed
  // suggestion list is not worth an error message on somebody's episode page.
  if (error || !episodes || episodes.length === 0) {
    return null;
  }
  const data = episodes;

  return (
    <section className="mc-related" aria-labelledby="mc-related-heading">
      <h2 className="mc-related__heading" id="mc-related-heading">
        {t('episode.related')}
      </h2>
      <ul className="mc-related__list">
        {data.map((episode) => (
          <li key={episode.id} className="mc-related__item">
            <Link className="mc-related__link" to={`/episodes/${episode.slug}`}>
              <Cover id={episode.id} imageUrl={episode.imageUrl} size={56} />
              <span className="mc-related__text">
                <span className="mc-related__title">{episode.title}</span>
                {episode.season != null && episode.episodeNo != null && (
                  <span className="mc-related__meta mc-muted">
                    S{episode.season} · E{episode.episodeNo}
                  </span>
                )}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
