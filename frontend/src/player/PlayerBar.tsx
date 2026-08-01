// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { Cover } from '../components/Cover';
import { SlotRegion } from '../components/SlotRegion';
import { formatDuration } from '../util/format';
import { PLAYBACK_RATES, usePlayer } from './PlayerContext';

/**
 * The persistent player bar (`data-slot="player"`): cover + now-playing, transport with ±15/30 s and a
 * speed cycle, a scrubber that shows how far in you are, and volume. Always mounted while an episode is
 * loaded, across route changes. Hosts the `player` plugin slot region.
 *
 * **Keyboard is a first-class input here** (BRIEF E4, WCAG AA): space toggles, arrows scrub, `J`/`L` jump
 * the podcast-standard 15/30 s. The handler is on `document` because a player that only responds while its
 * own button has focus is not a keyboard-operable player — and it stands down inside inputs, so typing a
 * search query does not seek the audio.
 */
export function PlayerBar() {
  const { t } = useTranslation();
  const { current, playing, currentTime, duration, volume, rate, toggle, seek, skip, setVolume, setRate } =
    usePlayer();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      // Never steal a key from something the visitor is typing into — including a plugin's shadow DOM,
      // where the event target is the host element rather than the field inside it.
      if (
        target?.closest('input, textarea, select, [contenteditable="true"]') ||
        target?.shadowRoot?.activeElement
      ) {
        return;
      }
      switch (event.key) {
        case ' ':
          event.preventDefault();
          toggle();
          break;
        case 'ArrowLeft':
          skip(-5);
          break;
        case 'ArrowRight':
          skip(5);
          break;
        case 'j':
        case 'J':
          skip(-15);
          break;
        case 'l':
        case 'L':
          skip(30);
          break;
        default:
          break;
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [toggle, skip]);

  if (!current) {
    return null;
  }

  const seasonEp =
    current.season != null && current.episodeNo != null
      ? `S${String(current.season).padStart(2, '0')} · E${String(current.episodeNo).padStart(2, '0')}`
      : null;
  const detailPath = `/episodes/${current.slug}`;
  const played = duration > 0 ? (currentTime / duration) * 100 : 0;
  const rateIndex = PLAYBACK_RATES.indexOf(rate as (typeof PLAYBACK_RATES)[number]);
  const nextRate = PLAYBACK_RATES[(rateIndex + 1) % PLAYBACK_RATES.length];

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
        <div className="mc-player__controls">
          <button
            type="button"
            className="mc-player__skip"
            onClick={() => skip(-15)}
            aria-label={t('player.back15')}
          >
            ↺<span className="mc-player__skipnum">15</span>
          </button>
          <button
            type="button"
            className="mc-player__play"
            onClick={toggle}
            aria-label={playing ? t('player.pause') : t('player.play')}
          >
            {playing ? '❚❚' : '▶'}
          </button>
          <button
            type="button"
            className="mc-player__skip"
            onClick={() => skip(30)}
            aria-label={t('player.forward30')}
          >
            ↻<span className="mc-player__skipnum">30</span>
          </button>
        </div>

        <span className="mc-player__time">{formatDuration(Math.floor(currentTime))}</span>
        {/* The played part of the track is painted from a custom property rather than a second element —
            one input stays one control for assistive tech and for keyboard seeking. */}
        <input
          className="mc-player__scrub"
          style={{ '--mc-played': `${played}%` } as CSSProperties}
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
        <button
          type="button"
          className="mc-player__rate"
          onClick={() => setRate(nextRate)}
          aria-label={t('player.speed', { rate })}
        >
          {rate}×
        </button>
        <SlotRegion name="player" scope={{ type: 'episode', id: current.slug }} />
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
