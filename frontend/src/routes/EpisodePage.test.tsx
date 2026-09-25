// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { EpisodePage } from './EpisodePage';

/**
 * The `?t=` half of episode sharing (§6.4). The player itself is mocked: jsdom implements no media methods,
 * and what this page owes the player is a single call with the position the URL asked for — the seek is
 * `PlayerContext`'s business, not the page's.
 */
const play = vi.fn();
vi.mock('../player/PlayerContext', () => ({
  usePlayer: () => ({ play }),
  usePlayerActions: () => ({ play }),
}));
vi.mock('../components/FeedsContext', () => ({
  useFeeds: () => ({ titleOf: () => 'A Feed' }),
}));
vi.mock('../components/SlotRegion', () => ({
  SlotRegion: () => null,
}));
vi.mock('../components/RelatedPins', () => ({
  RelatedPins: () => null,
}));

const EPISODE = {
  id: '11111111-1111-1111-1111-111111111111',
  slug: 'kraken',
  feedId: '22222222-2222-2222-2222-222222222222',
  season: 2,
  episodeNo: 7,
  status: 'PUBLISHED',
  access: 'PUBLIC',
  title: 'The Kraken',
  subtitle: null,
  author: 'A Host',
  imageUrl: null,
  description: '<p>Deep water.</p>',
  publishedAt: '2026-03-04T10:00:00Z',
  durationSeconds: 2520,
  audioUrl: 'https://cdn.test/kraken.mp3',
};

function stubFetch(episode: unknown = EPISODE) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve({
        ok: true,
        status: 200,
        text: () =>
          Promise.resolve(JSON.stringify(url.endsWith('/kraken') ? episode : url.includes('related') ? [] : {})),
      }),
    ),
  );
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/episodes/:slug" element={<EpisodePage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('EpisodePage timestamp links (§6.4)', () => {
  beforeEach(() => play.mockClear());
  afterEach(() => vi.unstubAllGlobals());

  it('arms the player at the shared position', async () => {
    stubFetch();
    renderAt('/episodes/kraken?t=754');

    await waitFor(() => expect(play).toHaveBeenCalled());
    expect(play).toHaveBeenCalledWith(expect.objectContaining({ slug: 'kraken' }), { startAt: 754 });
  });

  it('accepts a clock timestamp and labels the button with it', async () => {
    stubFetch();
    renderAt('/episodes/kraken?t=12:34');

    await waitFor(() => expect(play).toHaveBeenCalledWith(expect.anything(), { startAt: 754 }));
    expect(await screen.findByRole('button', { name: /12:34/ })).toBeInTheDocument();
  });

  it('ignores an unparsable timestamp and starts nothing', async () => {
    stubFetch();
    renderAt('/episodes/kraken?t=nonsense');

    expect(await screen.findByRole('button', { name: /Play/ })).toBeInTheDocument();
    expect(play).not.toHaveBeenCalled();
  });

  it('does not auto-start an episode with no playable audio', async () => {
    stubFetch({ ...EPISODE, status: 'PLANNED', audioUrl: null });
    renderAt('/episodes/kraken?t=754');

    expect(await screen.findByText('The Kraken')).toBeInTheDocument();
    expect(play).not.toHaveBeenCalled();
  });
});

describe('EpisodePage when the episode cannot be loaded (core#185)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('says so and offers a retry, instead of "Loading…" forever', async () => {
    // Only a 404 was inspected: a 5xx, a dropped connection or an offline tab fell through to the loading
    // copy with no message and no way out.
    let episodeCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.endsWith('/kraken') && episodeCalls++ === 0) {
          return Promise.resolve({ ok: false, status: 503, statusText: '', json: () => Promise.reject(new Error()) });
        }
        return Promise.resolve({
          ok: true,
          status: 200,
          text: () =>
            Promise.resolve(JSON.stringify(url.endsWith('/kraken') ? EPISODE : url.includes('related') ? [] : {})),
        });
      }),
    );
    renderAt('/episodes/kraken');

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('The Kraken')).toBeInTheDocument();
  });
});
