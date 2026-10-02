// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, act, screen } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';

import type { FilterState, PluginContext, Scope } from '@mosaicast/plugin-sdk';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { DEFINE_TIMEOUT_MS, PluginMount, toFilterState } from './PluginMount';

/**
 * What this pins is an identity, not a render count (ARCHITECTURE §7.5).
 *
 * The SDK's contract is that assigning `ctx` re-renders the element, and before 0.15.0 it did so by running
 * the previous render's cleanup first — a full teardown. The player's context value is rebuilt on every one
 * of its own renders and its `currentTime` is state that moves on every `timeupdate`, so `ctx` was a new
 * object several times a second while audio played and every mounted plugin was destroyed and rebuilt at
 * that rate. The fix is that playback is not a `ctx` input; a language change still is.
 */

let playerTime = 0;
const setPlayerTime: (seconds: number) => void = () => {};

vi.mock('../player/PlayerContext', () => ({
  // Rebuilt on every read, exactly like the real provider's value: the point is that PluginMount does not
  // take the identity of this object as an input. The position is a getter here for the same reason it is
  // one on the real actions context — reading it must not make the reader a tick-rate consumer.
  usePlayerActions: () => ({
    getCurrentTime: () => playerTime,
    seek: (s: number) => setPlayerTime(s),
  }),
}));
vi.mock('../auth/UserContext', () => ({ useUser: () => ({ user: null }) }));
// Hoisted for the same reason as the consent value: the real SiteContext memoises its value, so a mock that
// rebuilt it per render would be testing the mock.
const siteValue = { site: { theme: { light: {}, dark: {} } }, mode: 'light' as const };
vi.mock('../theme/SiteContext', () => ({ useSite: () => siteValue }));
// Stable across renders, like the real provider's `useCallback`s — an unstable one would rebuild `ctx` for
// reasons that have nothing to do with what this test is measuring.
const consentValue = {
  has: () => true,
  granted: [] as string[],
  request: () => {},
  subscribe: () => () => {},
};
vi.mock('../consent/ConsentContext', () => ({ useConsent: () => consentValue }));

/** Records every `ctx` assignment the host makes. */
const assignments: unknown[] = [];

// Hoisted, because a fresh object literal per render is itself a `ctx` input — the test would then measure
// its own props rather than the player.
const SITE_SCOPE: Scope = { type: 'site', id: 'site' };
const FEED_SCOPE: Scope = { type: 'feed', id: 'other' };
const NO_EPISODES: string[] = [];
const NO_LABELS: Record<string, string> = {};

beforeAll(() => {
  class StubPluginElement extends HTMLElement {
    set ctx(value: unknown) {
      assignments.push(value);
    }
  }
  customElements.define('mc-stub-plugin', StubPluginElement);
});

function mount(scope: Scope = SITE_SCOPE) {
  return (
    <MemoryRouter>
      <PluginMount
        pluginId="stub"
        tag="mc-stub-plugin"
        scope={scope}
        episodes={NO_EPISODES}
        episodeLabels={NO_LABELS}
      />
    </MemoryRouter>
  );
}

function renderMount() {
  return render(mount());
}

describe('PluginMount', () => {
  it('does not reassign ctx while the player ticks', async () => {
    assignments.length = 0;
    const { rerender } = renderMount();
    await act(async () => {});
    const afterMount = assignments.length;
    expect(afterMount).toBeGreaterThan(0);

    // Four `timeupdate`s worth of playback, which is about one second of audio.
    for (const seconds of [1, 2, 3, 4]) {
      playerTime = seconds;
      await act(async () => {
        rerender(mount());
      });
    }

    expect(assignments.length).toBe(afterMount);
  });

  it('does not reassign ctx for a scope object that only looks new', async () => {
    // Every region writes its scope inline — `scope={{ type: 'episode', id: slug }}` — so the object is a
    // new one on every render of whatever hosts it, while the scope it describes has not moved. Taking the
    // object as an input is what turned one visitor's feed page into ~304 requests a second (core#158), so
    // the values are the input and the object is not.
    assignments.length = 0;
    const { rerender } = render(mount({ type: 'episode', id: 'the-same-episode' }));
    await act(async () => {});
    const afterMount = assignments.length;
    expect(afterMount).toBeGreaterThan(0);

    for (let i = 0; i < 4; i += 1) {
      await act(async () => {
        rerender(mount({ type: 'episode', id: 'the-same-episode' }));
      });
    }

    expect(assignments.length).toBe(afterMount);
  });

  it('still hands the element a new ctx when something it cares about changes', async () => {
    assignments.length = 0;
    const { rerender } = renderMount();
    await act(async () => {});
    const afterMount = assignments.length;

    await act(async () => {
      rerender(mount(FEED_SCOPE));
    });

    expect(assignments.length).toBeGreaterThan(afterMount);
  });

  it('hands an episode tile where its episode stands, and a new ctx when that moves (core#252)', async () => {
    const tile = (phase: string, announceAt?: string) => (
      <MemoryRouter>
        <PluginMount
          pluginId="stub"
          tag="mc-stub-plugin"
          scope={{ type: 'episode', id: 'next-week' }}
          episodes={NO_EPISODES}
          episodeLabels={NO_LABELS}
          episodeStatus={phase === 'RELEASED' ? 'PUBLISHED' : 'PLANNED'}
          episodePhase={phase}
          episodeAnnounceAt={announceAt}
        />
      </MemoryRouter>
    );
    assignments.length = 0;
    const { rerender } = render(tile('UPCOMING', '2026-11-01T18:00:00Z'));
    await act(async () => {});
    expect((assignments.at(-1) as { episode?: unknown }).episode).toEqual({
      status: 'PLANNED',
      phase: 'upcoming',
      announceAt: '2026-11-01T18:00:00Z',
    });
    const afterMount = assignments.length;

    await act(async () => {
      rerender(tile('UPCOMING', '2026-11-01T18:00:00Z'));
    });
    expect(assignments.length).toBe(afterMount);

    await act(async () => {
      rerender(tile('RELEASED'));
    });
    expect((assignments.at(-1) as { episode?: unknown }).episode).toEqual({ status: 'PUBLISHED', phase: 'released' });
  });

  describe('failures no error boundary can see (core#185)', () => {
    it('shows the failed tile when the element throws on its ctx, instead of an unhandled rejection', async () => {
      // The SDK renders synchronously on assignment, inside a promise callback: the throw used to become an
      // unhandled rejection — no tile, no console line, and the isolation promise quietly not holding.
      class ThrowingElement extends HTMLElement {
        set ctx(_: unknown) {
          throw new Error('render exploded');
        }
      }
      customElements.define('mc-throwing-plugin', ThrowingElement);
      const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
      try {
        render(
          <MemoryRouter>
            <PluginMount pluginId="boom" tag="mc-throwing-plugin" scope={SITE_SCOPE} episodes={NO_EPISODES}
              episodeLabels={NO_LABELS} />
          </MemoryRouter>,
        );

        expect(await screen.findByRole('status')).toHaveTextContent('This part of the page could not be shown.');
        expect(logged).toHaveBeenCalledWith(expect.stringContaining("'boom'"), expect.any(Error));
      } finally {
        logged.mockRestore();
      }
    });

    it('gives up on a bundle that never defines its element', async () => {
      vi.useFakeTimers();
      const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
      try {
        render(
          <MemoryRouter>
            <PluginMount pluginId="ghost" tag="mc-never-defined" scope={SITE_SCOPE} episodes={NO_EPISODES}
              episodeLabels={NO_LABELS} />
          </MemoryRouter>,
        );
        expect(screen.queryByRole('status')).not.toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(DEFINE_TIMEOUT_MS);
        });

        expect(screen.getByRole('status')).toHaveTextContent('This part of the page could not be shown.');
      } finally {
        logged.mockRestore();
        vi.useRealTimers();
      }
    });
  });

  describe('ctx.filter (core#248)', () => {
    let go: (to: string) => void = () => {};
    function Navigator() {
      const navigate = useNavigate();
      go = (to) => navigate(to);
      return null;
    }
    function filtered(initial: string, routePath?: string) {
      return (
        <MemoryRouter initialEntries={[initial]}>
          <Navigator />
          <PluginMount
            pluginId="stub"
            tag="mc-stub-plugin"
            scope={FEED_SCOPE}
            episodes={NO_EPISODES}
            episodeLabels={NO_LABELS}
            routePath={routePath}
          />
        </MemoryRouter>
      );
    }
    const lastCtx = () => assignments[assignments.length - 1] as PluginContext;

    it('maps the shell\'s URL filters to FilterState, and nothing else', () => {
      expect(toFilterState('5', 'fog', 'oldest')).toEqual({ season: 5, tags: ['fog'], sort: 'oldest' });
      expect(toFilterState('', '', '')).toEqual({});
      expect(toFilterState('five', '', '')).toEqual({});
    });

    it('hands a feed tile the season the visitor picked, and tells it when that changes', async () => {
      assignments.length = 0;
      render(filtered('/feeds/the-cast?season=5&tag=fog&t=12'));
      await act(async () => {});
      expect(lastCtx().filter.current()).toEqual({ season: 5, tags: ['fog'] });
      const heard: FilterState[] = [];
      lastCtx().filter.onChange((f) => heard.push(f));
      const before = assignments.length;

      // A query parameter that is not a filter changes nothing for the tile.
      await act(async () => go('/feeds/the-cast?season=5&tag=fog&t=99'));
      expect(assignments.length).toBe(before);
      expect(heard).toEqual([]);

      await act(async () => go('/feeds/the-cast?season=4'));
      expect(assignments.length).toBe(before + 1);
      expect(lastCtx().filter.current()).toEqual({ season: 4 });
      // The listener registered through the previous ctx still hears it.
      expect(heard).toEqual([{ season: 4 }]);
    });

    it('leaves a plugin page\'s own query alone: a page is not a filtered list', async () => {
      assignments.length = 0;
      render(filtered('/p/stub/board?season=5', 'board'));
      await act(async () => {});
      expect(lastCtx().filter.current()).toEqual({});
    });
  });
});
