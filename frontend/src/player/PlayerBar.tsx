// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { Cover } from '../components/Cover';
import { Icon } from '../components/Icon';
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
 * own button has focus is not a keyboard-operable player — and it stands down wherever the focused element
 * already owns the key, so typing a search query does not seek the audio and Space still presses the button
 * a visitor tabbed to. A document-level shortcut that shadows a control is a WCAG 2.1.1 failure, not a
 * convenience.
 */
/**
 * Elements that answer to Space, Enter or the arrows on their own.
 *
 * Split out from the handler so the rule is testable without mounting the player and its provider stack — the
 * regression it guards against is invisible in a render test but obvious here.
 */
const OWNS_ITS_KEYS =
  'input, textarea, select, button, a[href], summary, [contenteditable="true"], ' +
  '[role="button"], [role="link"], [role="checkbox"], [role="switch"], [role="tab"], [role="menuitem"], ' +
  '[role="option"], [role="radio"], [role="slider"], [role="spinbutton"], [role="textbox"]';

/**
 * Whether the player's document-level shortcuts must stand down for this event target.
 *
 * Two reasons they must. The visitor may be typing, and a search box that seeks the audio is not a search box.
 * And the focused element may already own the key: Space activates a focused button, which a browser implements
 * by *watching for the default action* — Blink and Gecko both abandon the activation the moment
 * `defaultPrevented` is set on the keydown. A blanket `preventDefault()` therefore does not add a shortcut, it
 * takes every button in the shell away from anyone navigating by keyboard, the consent banner's Accept and
 * Reject included. That is a WCAG 2.1.1 failure, not a convenience.
 */
export function ownsItsKeys(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  return Boolean(element?.closest?.(OWNS_ITS_KEYS) || element?.shadowRoot?.activeElement);
}

export function PlayerBar() {
  const { t } = useTranslation();
  const {
    current, playing, currentTime, duration, volume, rate, problem, toggle, seek, skip, setVolume, setRate,
    retry, stop,
  } = usePlayer();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (ownsItsKeys(event.target)) {
        return;
      }
      switch (event.key) {
        case ' ':
          event.preventDefault();
          toggle();
          break;
        case 'ArrowLeft':
          // Seeking without this also scrolls the page — the arrow keys keep their default action otherwise.
          event.preventDefault();
          skip(-5);
          break;
        case 'ArrowRight':
          event.preventDefault();
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
      {/* What used to be a dead, paused bar with nothing said (core#170). Its own strip along the bar's top
          edge rather than a line under the title: the title column is a hundred-odd pixels on a phone, and
          a notice that has to be truncated there — with its button cut off — says nothing. A status, so a
          screen reader hears it without the focus moving. */}
      {problem && (
        <p className="mc-player__problem" role="status">
          {problem === 'error' ? t('player.unplayable') : t('player.blocked')}
          {problem === 'error' && (
            <button type="button" className="mc-player__retry" onClick={retry}>
              {t('player.retry')}
            </button>
          )}
        </p>
      )}
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
            <Icon name="skip-back" />
            <span className="mc-player__skipnum">15</span>
          </button>
          <button
            type="button"
            className="mc-player__play"
            onClick={toggle}
            aria-label={playing ? t('player.pause') : t('player.play')}
          >
            <Icon name={playing ? 'pause' : 'play'} />
          </button>
          <button
            type="button"
            className="mc-player__skip"
            onClick={() => skip(30)}
            aria-label={t('player.forward30')}
          >
            <Icon name="skip-forward" />
            <span className="mc-player__skipnum">30</span>
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
        <SlotRegion name="player" scope={{ type: 'episode', id: current.slug }} scopeLabel={current.title} />
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
        {/* There was no way to close the bar: `current` never went back to null once anything had played. */}
        <button type="button" className="mc-player__close" onClick={stop} aria-label={t('player.close')} />
      </div>
    </div>
  );
}
