// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { PlayerProvider } from '../player/PlayerContext';
import { EpisodeFeed } from './EpisodeFeed';

/** Routes a fetch by URL to canned JSON, so the feed can render without a backend. */
function mockApi(url: string) {
  const json = (data: unknown) =>
    Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(data)) });
  if (url.startsWith('/api/feeds/') && url.endsWith('/seasons')) {
    return json([1, 2]);
  }
  if (url === '/api/feeds') {
    return json([{ id: 'f1', title: 'Test Cast', episodeCount: 2 }]);
  }
  if (url.startsWith('/api/episodes')) {
    return json({
      items: [
        {
          id: 'e1',
          feedId: 'f1',
          season: 2,
          episodeNo: 12,
          status: 'PUBLISHED',
          access: 'PUBLIC',
          accessTierRef: null,
          title: 'Why pigeons secretly hate us',
          publishedAt: '2026-06-21T00:00:00Z',
          durationSeconds: 4122,
          hasAudio: true,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
  }
  return json([]);
}

describe('EpisodeFeed', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => mockApi(url)),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('renders episode cards and the order filter', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <PlayerProvider>
          <EpisodeFeed />
        </PlayerProvider>
      </MemoryRouter>,
    );
    // The card (content) renders from the site-scope list.
    expect(await screen.findByRole('link', { name: 'Why pigeons secretly hate us' })).toBeInTheDocument();
    // The ordering filter axis is present (episodes are content, filters narrow them).
    expect(screen.getByText('Order')).toBeInTheDocument();
  });
});
