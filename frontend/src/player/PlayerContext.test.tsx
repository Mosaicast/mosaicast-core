// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { NOW_PLAYING_KEY } from './nowPlaying';
import { isPlaybackBusy } from './playbackGate';
import { PlayerProvider, usePlayerActions, type PlayableEpisode } from './PlayerContext';

/**
 * The player had no tests at all (core#170), and it is the product's central promise. jsdom implements no
 * media playback, so the element is faked at the prototype: `play()` resolves (or rejects, per test) and
 * fires the events a browser would, and the Media Session is a recorder.
 */

const get = vi.fn();
const post = vi.fn(() => Promise.resolve(undefined));
const put = vi.fn(() => Promise.resolve(undefined));
vi.mock('../api/client', () => ({
  api: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...(args as [])),
    put: (...args: unknown[]) => put(...(args as [])),
  },
}));
vi.mock('../auth/UserContext', () => ({ useUser: () => ({ user: null }) }));
vi.mock('../components/SlotRegion', () => ({ SlotRegion: () => null }));

type FakeMedia = HTMLMediaElement & { fakePaused?: boolean };

let playImpl: ((media: FakeMedia) => Promise<void>) | null = null;
const handlers = new Map<string, MediaSessionActionHandler | null>();
const setActionHandler = vi.fn((action: string, handler: MediaSessionActionHandler | null) => {
  handlers.set(action, handler);
});
const session = { metadata: null as unknown, playbackState: 'none', setActionHandler, setPositionState: vi.fn() };

function audio(): FakeMedia {
  return document.querySelector('audio') as FakeMedia;
}

const EPISODE: PlayableEpisode = {
  id: 'e1',
  slug: 'kraken',
  title: 'The Kraken',
  audioUrl: 'https://cdn.test/kraken.mp3',
  feedTitle: 'Deep Water',
};

let actions: ReturnType<typeof usePlayerActions>;
function Capture() {
  actions = usePlayerActions();
  return null;
}

function renderPlayer() {
  return render(
    <MemoryRouter>
      <PlayerProvider>
        <Capture />
      </PlayerProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  get.mockReset();
  post.mockClear();
  put.mockClear();
  handlers.clear();
  setActionHandler.mockClear();
  playImpl = null;
  Object.defineProperty(HTMLMediaElement.prototype, 'paused', {
    configurable: true,
    get(this: FakeMedia) {
      return this.fakePaused ?? true;
    },
  });
  HTMLMediaElement.prototype.play = function play(this: FakeMedia) {
    if (playImpl) {
      return playImpl(this);
    }
    this.fakePaused = false;
    this.dispatchEvent(new Event('play'));
    return Promise.resolve();
  };
  HTMLMediaElement.prototype.pause = function pause(this: FakeMedia) {
    this.fakePaused = true;
    this.dispatchEvent(new Event('pause'));
  };
  HTMLMediaElement.prototype.load = () => {};
  vi.stubGlobal('navigator', { ...navigator, mediaSession: session });
  vi.stubGlobal(
    'MediaMetadata',
    class {
      constructor(init: object) {
        Object.assign(this, init);
      }
    },
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PlayerProvider', () => {
  describe('after a full page load (core#168)', () => {
    it('brings the bar back, paused, where it was — without touching the audio host', () => {
      localStorage.setItem(
        NOW_PLAYING_KEY,
        JSON.stringify({ episode: EPISODE, position: 754, duration: 3120 }),
      );

      renderPlayer();

      expect(screen.getByRole('region', { name: 'Player' })).toBeInTheDocument();
      expect(screen.getByText('The Kraken')).toBeInTheDocument();
      expect(screen.getByText('12:34')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Play' })).toBeInTheDocument();
      // No source yet: pointing the element at a third-party enclosure is a request the visitor did not
      // make on this page view. It happens on the press of play.
      expect(audio().getAttribute('src')).toBeNull();
    });

    it('loads the source on the first press and resumes from the restored position', async () => {
      localStorage.setItem(NOW_PLAYING_KEY, JSON.stringify({ episode: EPISODE, position: 754, duration: 0 }));
      renderPlayer();

      fireEvent.click(screen.getByRole('button', { name: 'Play' }));

      await waitFor(() => expect(audio().getAttribute('src')).toBe(EPISODE.audioUrl));
      Object.defineProperty(audio(), 'duration', { configurable: true, value: 3120 });
      act(() => {
        audio().dispatchEvent(new Event('loadedmetadata'));
      });
      expect(audio().currentTime).toBe(754);
    });

    it('restores nothing when remembering is switched off', () => {
      localStorage.setItem('mc.prefs.progress', 'off');
      localStorage.setItem(NOW_PLAYING_KEY, JSON.stringify({ episode: EPISODE, position: 754, duration: 0 }));

      renderPlayer();

      expect(screen.queryByRole('region', { name: 'Player' })).not.toBeInTheDocument();
    });

    it('ignores a record it did not write rather than failing to boot', () => {
      localStorage.setItem(NOW_PLAYING_KEY, '{"episode":{"id":1}}');

      renderPlayer();

      expect(screen.queryByRole('region', { name: 'Player' })).not.toBeInTheDocument();
    });

    it('keeps the record while playing, and drops it when the bar is closed', async () => {
      renderPlayer();

      await act(() => actions.play(EPISODE));
      expect(JSON.parse(localStorage.getItem(NOW_PLAYING_KEY) ?? '{}').episode.slug).toBe('kraken');

      fireEvent.click(screen.getByRole('button', { name: 'Close the player' }));

      expect(screen.queryByRole('region', { name: 'Player' })).not.toBeInTheDocument();
      expect(localStorage.getItem(NOW_PLAYING_KEY)).toBeNull();
      expect(audio().getAttribute('src')).toBeNull();
    });
  });

  describe('play() (core#170)', () => {
    it('lets the last call win when an earlier one is still waiting for its audio URL', async () => {
      // The detail fetch for the first episode answers *after* the second press. It used to win anyway: the
      // bar showed one episode and the element loaded the other.
      let answerFirst: (value: unknown) => void = () => {};
      get.mockImplementation((url: string) =>
        url.endsWith('/slow')
          ? new Promise((resolve) => {
            answerFirst = resolve;
          })
          : Promise.resolve({ audioUrl: 'https://cdn.test/fast.mp3' }),
      );
      renderPlayer();

      let first: Promise<void> = Promise.resolve();
      act(() => {
        first = actions.play({ id: 'a', slug: 'slow', title: 'Slow' });
      });
      await act(() => actions.play({ id: 'b', slug: 'fast', title: 'Fast' }));
      await act(async () => {
        answerFirst({ audioUrl: 'https://cdn.test/slow.mp3' });
        await first;
      });

      expect(audio().getAttribute('src')).toBe('https://cdn.test/fast.mp3');
      expect(screen.getByText('Fast')).toBeInTheDocument();
      expect(screen.queryByText('Slow')).not.toBeInTheDocument();
    });

    it('says so when the browser refuses to start without a gesture', async () => {
      // What every `?t=` deep link meets: play() from an effect, not a click. It used to be swallowed.
      playImpl = () => Promise.reject(new DOMException('no gesture', 'NotAllowedError'));
      renderPlayer();

      await act(() => actions.play(EPISODE, { startAt: 90 }));

      expect(screen.getByRole('status')).toHaveTextContent('Press play to start.');
    });

    it('says so when the audio cannot be played, offers a retry, and tells the operator once', async () => {
      renderPlayer();
      await act(() => actions.play(EPISODE));

      Object.defineProperty(audio(), 'error', { configurable: true, value: { code: 4 } });
      act(() => {
        audio().dispatchEvent(new Event('error'));
      });

      expect(screen.getByRole('status')).toHaveTextContent('This episode could not be played.');
      expect(post).toHaveBeenCalledWith('/api/episodes/kraken/playback-error', { code: 4 });

      // A retry that fails again is the same episode: one report per page view is enough.
      fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
      act(() => {
        audio().dispatchEvent(new Event('error'));
      });
      expect(post).toHaveBeenCalledTimes(1);
    });
  });

  describe('Media Session (core#170)', () => {
    it('registers its handlers once per episode, not on every tick, and names the show', async () => {
      renderPlayer();
      await act(() => actions.play(EPISODE));
      const registrations = setActionHandler.mock.calls.length;

      for (let i = 1; i <= 8; i++) {
        Object.defineProperty(audio(), 'currentTime', { configurable: true, writable: true, value: i });
        act(() => {
          audio().dispatchEvent(new Event('timeupdate'));
        });
      }

      expect(setActionHandler.mock.calls.length).toBe(registrations);
      expect(session.metadata).toMatchObject({ title: 'The Kraken', artist: 'Deep Water', album: 'Deep Water' });
      expect(session.playbackState).toBe('playing');
    });

    it('takes its handlers away when the bar closes, so nothing calls into a closed player', async () => {
      renderPlayer();
      await act(() => actions.play(EPISODE));

      act(() => actions.stop());

      expect(handlers.get('play')).toBeNull();
      expect(handlers.get('nexttrack')).toBeNull();
      expect(session.metadata).toBeNull();
    });
  });

  it('keeps playing when storage refuses every write (core#185)', async () => {
    // Blocked or full storage used to throw from the `timeupdate` handler several times a second, where no
    // error boundary can reach.
    renderPlayer();
    await act(() => actions.play(EPISODE));
    const errors: unknown[] = [];
    const onError = (event: ErrorEvent) => errors.push(event.error);
    window.addEventListener('error', onError);
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });
    try {
      for (let i = 1; i <= 3; i++) {
        Object.defineProperty(audio(), 'currentTime', { configurable: true, writable: true, value: i });
        act(() => {
          audio().dispatchEvent(new Event('timeupdate'));
        });
      }
      act(() => {
        audio().dispatchEvent(new Event('ended'));
      });

      expect(errors).toEqual([]);
      expect(setItem).toHaveBeenCalled();
    } finally {
      setItem.mockRestore();
      window.removeEventListener('error', onError);
    }
  });

  it('remembers the volume across page loads, as it does the speed', () => {
    const first = renderPlayer();
    act(() => actions.setVolume(0.35));
    first.unmount();

    renderPlayer();

    expect(audio().volume).toBeCloseTo(0.35);
  });

  it('tells the consent code whether something is playing, so a reload can wait', async () => {
    renderPlayer();
    expect(isPlaybackBusy()).toBe(false);

    await act(() => actions.play(EPISODE));

    expect(isPlaybackBusy()).toBe(true);
  });
});
