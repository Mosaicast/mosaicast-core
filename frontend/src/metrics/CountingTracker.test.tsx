// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { flush, resetForTests } from './beacon';
import { CountingTracker } from './CountingTracker';

let meta: { counting?: boolean } | null = { counting: true };
let user: { role: string } | null = null;
let player = { current: null as { slug: string } | null, playing: false, currentTime: 0, duration: 0 };

vi.mock('../api/MetaContext', () => ({ useMeta: () => meta }));
vi.mock('../auth/UserContext', () => ({ useUser: () => ({ user, loading: false }) }));
vi.mock('../player/PlayerContext', () => ({ usePlayer: () => player }));

const fetchMock = vi.fn(async () => new Response(null, { status: 204 }));

let go: (path: string) => void = () => {};
function Navigator() {
  const navigate = useNavigate();
  go = navigate;
  return null;
}

function Tree({ path }: { path: string }) {
  return (
    <MemoryRouter initialEntries={[path]}>
      <Navigator />
      <CountingTracker />
      <Routes>
        <Route path="*" element={null} />
      </Routes>
    </MemoryRouter>
  );
}

function mount(path = '/') {
  return render(<Tree path={path} />);
}

function sent(): { kind: string; subject: string; milestone?: number }[] {
  act(() => flush());
  return fetchMock.mock.calls.flatMap((call) => JSON.parse(String((call as unknown as [string, RequestInit])[1].body)).events);
}

describe('CountingTracker', () => {
  beforeEach(() => {
    meta = { counting: true };
    user = null;
    player = { current: null, playing: false, currentTime: 0, duration: 0 };
    fetchMock.mockClear();
    vi.stubGlobal('fetch', fetchMock);
    resetForTests();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('sends one view per distinct public path, without credentials', () => {
    mount('/');
    act(() => go('/episodes/ep-1'));
    act(() => go('/'));
    act(() => go('/admin/reach'));

    expect(sent()).toEqual([
      { kind: 'view', subject: 'home' },
      { kind: 'view', subject: 'episode/ep-1' },
    ]);
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init.credentials).toBe('omit');
    expect(init.keepalive).toBe(true);
  });

  it('counts the first play and each milestone once', () => {
    player = { current: { slug: 'ep-1' }, playing: true, currentTime: 0, duration: 100 };
    const view = mount('/episodes/ep-1');
    player = { ...player, currentTime: 55 };
    view.rerender(<Tree path="/episodes/ep-1" />);
    player = { ...player, currentTime: 60 };
    view.rerender(<Tree path="/episodes/ep-1" />);

    const events = sent().filter((e) => e.kind !== 'view');
    expect(events).toEqual([
      { kind: 'play', subject: 'episode/ep-1' },
      { kind: 'progress', subject: 'episode/ep-1', milestone: 25 },
      { kind: 'progress', subject: 'episode/ep-1', milestone: 50 },
    ]);
  });

  it.each([
    ['an admin', () => (user = { role: 'admin' })],
    ['a podcaster', () => (user = { role: 'podcaster' })],
    ['counting switched off', () => (meta = { counting: false })],
    ['an older core without the flag', () => (meta = {})],
    ['Global Privacy Control', () => vi.stubGlobal('navigator', { ...navigator, globalPrivacyControl: true })],
  ])('stays silent for %s', (_label, arrange) => {
    arrange();
    mount('/');

    expect(sent()).toEqual([]);
  });
});
