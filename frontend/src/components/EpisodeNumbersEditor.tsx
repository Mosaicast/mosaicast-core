// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { api } from '../api/client';
import type { EpisodeNumbers } from '../api/types';
import { useUser } from '../auth/UserContext';
import { formatSeasonEpisode } from '../util/format';

const toField = (n: number | null) => (n == null ? '' : String(n));
const toNumber = (value: string) => (value.trim() === '' ? null : Number(value));

/**
 * Setting a released episode's season and episode number by hand (ARCHITECTURE §4.4, core#264), next to the
 * pin editor because it is the same kind of decision: editorial, about this episode, made while looking at it.
 *
 * The case it exists for is a prologue the podcaster calls episode 0. A feed cannot say so — Apple allows only
 * a non-zero `itunes:episode`, so Acast drops it — and the episode arrives with a season and no number. Once
 * set here, feed polls leave the numbers alone; "use the feed's numbers" hands them back at once.
 *
 * Visible to PODCASTER and ADMIN only, and never on a planned episode, whose numbers belong to the plan. The
 * server enforces both; this only decides whether to render the controls.
 */
export function EpisodeNumbersEditor({ slug, onChange }: { slug: string; onChange: () => void }) {
  const { t } = useTranslation();
  const { user } = useUser();
  const mayEdit = user?.role === 'podcaster' || user?.role === 'admin';

  const [numbers, setNumbers] = useState<EpisodeNumbers | null>(null);
  const [season, setSeason] = useState('');
  const [episodeNo, setEpisodeNo] = useState('');
  const [failed, setFailed] = useState(false);

  const show = (next: EpisodeNumbers) => {
    setNumbers(next);
    setSeason(toField(next.season));
    setEpisodeNo(toField(next.episodeNo));
  };

  useEffect(() => {
    if (!mayEdit) {
      return;
    }
    let active = true;
    api
      .get<EpisodeNumbers>(`/api/admin/episodes/${slug}/numbers`)
      .then((loaded) => active && show(loaded))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [slug, mayEdit]);

  const mutate = async (run: () => Promise<EpisodeNumbers>) => {
    setFailed(false);
    try {
      show(await run());
      // The hero, the player and every list read the numbers from the episode, so it is read again.
      onChange();
    } catch {
      setFailed(true);
    }
  };

  const save = (event: FormEvent) => {
    event.preventDefault();
    void mutate(() =>
      api.put<EpisodeNumbers>(`/api/admin/episodes/${slug}/numbers`, {
        season: toNumber(season),
        episodeNo: toNumber(episodeNo),
      }),
    );
  };

  if (!mayEdit || numbers == null) {
    return null;
  }

  const feedLabel = formatSeasonEpisode(numbers.feedSeason, numbers.feedEpisodeNo);
  const unchanged = toField(numbers.season) === season && toField(numbers.episodeNo) === episodeNo;

  return (
    <form className="mc-numbers" onSubmit={save}>
      <h2 className="mc-numbers__heading">{t('episode.numbers')}</h2>
      <p className="mc-muted mc-numbers__hint">{t('episode.numbersHint')}</p>
      <div className="mc-numbers__fields">
        <label className="mc-field">
          <span>{t('episode.numbersSeason')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={0}
            value={season}
            onChange={(e) => setSeason(e.target.value)}
          />
        </label>
        <label className="mc-field">
          <span>{t('episode.numbersEpisode')}</span>
          <input
            className="mc-input mc-input--num"
            type="number"
            min={0}
            value={episodeNo}
            onChange={(e) => setEpisodeNo(e.target.value)}
          />
        </label>
      </div>
      <p className="mc-muted mc-numbers__hint">
        {numbers.pinned && <>{t('episode.numbersPinned')} </>}
        {feedLabel ? t('episode.numbersFeed', { label: feedLabel }) : t('episode.numbersFeedNone')}
      </p>
      <div className="mc-numbers__actions">
        <button type="submit" className="mc-btn mc-btn--sm" disabled={unchanged && numbers.pinned}>
          {t('episode.numbersSave')}
        </button>
        {numbers.pinned && (
          <button
            type="button"
            className="mc-btn mc-btn--sm"
            onClick={() => void mutate(() => api.del<EpisodeNumbers>(`/api/admin/episodes/${slug}/numbers`))}
          >
            {t('episode.numbersReset')}
          </button>
        )}
      </div>
      {failed && (
        <p className="mc-muted" role="alert">
          {t('episode.numbersFailed')}
        </p>
      )}
    </form>
  );
}
