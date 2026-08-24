// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { SearchPage } from './SearchPage';

/**
 * The search page's job is to keep the sources apart. Ranking across them is not solvable, so what the UI
 * must never do is present a plugin's hit and an episode as one ordered list — and what it must never hide
 * is a section that failed to answer.
 */

vi.mock('../player/PlayerContext', () => ({
  usePlayer: () => ({ play: () => {}, currentTime: 0, seek: () => {} }),
}));

function mockSearch(body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        text: () => Promise.resolve(JSON.stringify(body)),
      }),
    ),
  );
}

const episode = {
  id: 'e1',
  slug: 'the-cast-s01e01',
  feedId: 'f1',
  season: 1,
  episodeNo: 1,
  status: 'PUBLISHED',
  access: 'PUBLIC',
  accessTierRef: null,
  title: 'Kraken Watching',
  subtitle: null,
  author: 'A Host',
  imageUrl: null,
  excerpt: 'On very large squid.',
  publishedAt: '2026-01-01T00:00:00Z',
  durationSeconds: 1800,
  hasAudio: true,
};

const renderAt = (query: string) =>
  render(
    <MemoryRouter initialEntries={[`/search?q=${encodeURIComponent(query)}`]}>
      <SearchPage />
    </MemoryRouter>,
  );

describe('SearchPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders one section per source', async () => {
    mockSearch({
      query: 'kraken',
      episodes: [episode],
      plugins: [
        {
          pluginId: 'wiki',
          name: 'Wiki',
          hits: [{ href: '/p/wiki/glossary/kraken', title: 'The Kraken', snippet: 'A very large squid.' }],
          timedOut: false,
        },
      ],
    });

    renderAt('kraken');

    expect(await screen.findByText('Kraken Watching')).toBeInTheDocument();
    // The plugin's section carries the plugin's own name, so a visitor can tell where an answer came from.
    expect(screen.getByRole('heading', { name: 'Wiki' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /The Kraken/ })).toHaveAttribute(
      'href',
      '/p/wiki/glossary/kraken',
    );
  });

  it('says so when a section did not answer in time', async () => {
    mockSearch({
      query: 'kraken',
      episodes: [],
      plugins: [{ pluginId: 'wiki', name: 'Wiki', hits: [], timedOut: true }],
    });

    renderAt('kraken');

    // Not silence: "found nothing" and "did not answer" are different answers, and a visitor given the
    // first when the second is true concludes the content is not on this site.
    expect(await screen.findByText(/did not answer in time/i)).toBeInTheDocument();
  });

  it('asks for nothing until there is a query', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    render(
      <MemoryRouter initialEntries={['/search']}>
        <SearchPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('searchbox')).toBeInTheDocument());
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
