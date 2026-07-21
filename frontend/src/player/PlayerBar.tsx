// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { Cover } from '../components/Cover';
import { SlotRegion } from '../components/SlotRegion';
import { formatDuration } from '../util/format';
import { usePlayer } from './PlayerContext';

/**
 * The persistent player bar (mockup `data-slot="player"`): cover + now-playing (feed · S·E · title),
 * transport, a seek scrubber, and volume. The cover and title link to the episode detail page. Always
 * mounted while an episode is loaded, across route changes. Hosts the `player` plugin slot region.
 */
export function PlayerBar() {
  const { t } = useTranslation();
  const { current, playing, currentTime, duration, volume, toggle, seek, setVolume } = usePlayer();

  if (!current) {
    return null;
  }

  const seasonEp =
    current.season != null && current.episodeNo != null
      ? `S${String(current.season).padStart(2, '0')} · E${String(current.episodeNo).padStart(2, '0')}`
      : null;
  const detailPath = `/episodes/${current.id}`;

  return (
    <div className="mc-player" data-slot="player" role="region" aria-label={t('player.region')}>
      <div className="mc-player__now">
        <Link to={detailPath} className="mc-player__cover" aria-hidden="true" tabIndex={-1}>
          <Cover id={current.id} imageUrl={current.imageUrl ?? null} size={52} />
        </Link>
        <div className="mc-player__meta">
          <div className="mc-player__sub mc-muted">
            {current.feedTitle && <span>{current.feedTitle}</span>}
            {seasonEp && <span>{seasonEp}</span>}
          </div>
          <Link to={detailPath} className="mc-player__title">
            {current.title}
          </Link>
        </div>
      </div>

      <div className="mc-player__transport">
        <button
          type="button"
          className="mc-player__play"
          onClick={toggle}
          aria-label={playing ? t('player.pause') : t('player.play')}
        >
          {playing ? '❚❚' : '▶'}
        </button>
        <span className="mc-player__time">{formatDuration(Math.floor(currentTime))}</span>
        <input
          className="mc-player__scrub"
          type="range"
          min={0}
          max={Math.max(1, Math.floor(duration))}
          value={Math.floor(currentTime)}
          onChange={(e) => seek(Number(e.target.value))}
          aria-label={t('player.seek')}
        />
        <span className="mc-player__time">{formatDuration(Math.floor(duration))}</span>
      </div>

      <div className="mc-player__right">
        <SlotRegion name="player" scope={{ type: 'episode', id: current.id }} />
        <input
          className="mc-player__vol"
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={volume}
          onChange={(e) => setVolume(Number(e.target.value))}
          aria-label={t('player.volume')}
        />
      </div>
    </div>
  );
}
