// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { FeedsProvider } from '../components/FeedsContext';
import { Home } from './Home';

/** Stands in for the unified list: what matters is whether Home ever started it. */
const feedMounts = vi.fn();
vi.mock('../components/EpisodeFeed', () => ({
  EpisodeFeed: () => {
    feedMounts();
    return <p>unified list</p>;
  },
}));

function stubFeeds(feeds: unknown[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(feeds)) })),
  );
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <FeedsProvider>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/feeds/:slug" element={<p>feed page</p>} />
        </Routes>
      </FeedsProvider>
    </MemoryRouter>,
  );
}

describe('Home (core#195)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    feedMounts.mockClear();
  });

  it('never starts the unified list on a single-feed site, which redirects anyway', async () => {
    // The list's page-0 query, tags and plugin fan-out used to start before the feed list arrived, and then
    // the redirect threw them away and the feed page asked again — the default install paid twice.
    stubFeeds([{ id: 'f1', slug: 'the-cast', title: 'The Cast', episodeCount: 6 }]);
    renderHome();

    expect(await screen.findByText('feed page')).toBeInTheDocument();
    expect(feedMounts).not.toHaveBeenCalled();
  });

  it('shows the unified list once it knows there is more than one feed', async () => {
    stubFeeds([
      { id: 'f1', slug: 'one', title: 'One', episodeCount: 1 },
      { id: 'f2', slug: 'two', title: 'Two', episodeCount: 1 },
    ]);
    renderHome();

    expect(await screen.findByText('unified list')).toBeInTheDocument();
  });
});
