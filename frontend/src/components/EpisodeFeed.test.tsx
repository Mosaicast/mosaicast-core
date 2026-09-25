// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, fireEvent, render, screen } from '@testing-library/react';
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

  it('drops a later page that answers after the filters changed (core#185)', async () => {
    // Page 2 of "newest" is still in flight when the visitor switches to "oldest". Its answer belongs to a
    // list that no longer exists; it used to be appended to the new one.
    const card = (id: string, title: string) => ({
      id, feedId: 'f1', season: 1, episodeNo: 1, status: 'PUBLISHED', access: 'PUBLIC', accessTierRef: null,
      title, publishedAt: '2026-06-21T00:00:00Z', durationSeconds: 60, hasAudio: true,
    });
    const page = (items: unknown[], totalPages: number) =>
      Promise.resolve({
        ok: true, status: 200,
        text: () => Promise.resolve(JSON.stringify({ items, page: 0, size: 20, totalElements: 2, totalPages })),
      });
    let answerStalePage: (value: unknown) => void = () => {};
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (!url.startsWith('/api/episodes')) {
          return mockApi(url);
        }
        if (url.includes('page=1')) {
          return new Promise((resolve) => {
            answerStalePage = resolve;
          });
        }
        return url.includes('order=oldest')
          ? page([card('o1', 'Oldest first')], 1)
          : page([card('n1', 'Newest first')], 2);
      }),
    );
    render(
      <MemoryRouter initialEntries={['/']}>
        <PlayerProvider>
          <EpisodeFeed />
        </PlayerProvider>
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    fireEvent.change(screen.getByLabelText('Order'), { target: { value: 'oldest' } });
    expect(await screen.findByRole('link', { name: 'Oldest first' })).toBeInTheDocument();
    await act(async () => {
      answerStalePage(await page([card('n2', 'From the old list')], 2));
    });

    expect(screen.queryByRole('link', { name: 'From the old list' })).not.toBeInTheDocument();
  });
});
