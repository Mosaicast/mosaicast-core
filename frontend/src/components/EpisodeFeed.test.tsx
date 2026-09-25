// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { PlayerProvider } from '../player/PlayerContext';
import { EpisodeFeed } from './EpisodeFeed';
import { FeedsProvider } from './FeedsContext';

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

  it('says how many a filter left out of how many, and clears it in one go (#199)', async () => {
    render(
      <MemoryRouter initialEntries={['/?tag=history']}>
        <FeedsProvider>
          <PlayerProvider>
            <EpisodeFeed />
          </PlayerProvider>
        </FeedsProvider>
      </MemoryRouter>,
    );

    // One match (the stub's page) of the two episodes the feed list says the site has.
    expect(await screen.findByText('1 of 2 episodes')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  describe('the paths other than the happy one (core#191)', () => {
    const card = (id: string, title: string) => ({
      id, feedId: 'f1', season: 1, episodeNo: 1, status: 'PUBLISHED', access: 'PUBLIC', accessTierRef: null,
      title, publishedAt: '2026-06-21T00:00:00Z', durationSeconds: 60, hasAudio: true,
    });
    const ok = (data: unknown) =>
      Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(data)) });
    const serverError = () =>
      Promise.resolve({ ok: false, status: 500, statusText: '', json: () => Promise.reject(new Error()) });
    const pageOf = (items: unknown[], page: number, totalPages: number) =>
      ok({ items, page, size: 1, totalElements: totalPages, totalPages });

    /** Episodes answered by `episodes(page)`; everything else by the shared mock. */
    function stubEpisodes(episodes: (page: number) => Promise<unknown>) {
      vi.stubGlobal(
        'fetch',
        vi.fn((url: string) => {
          if (!url.startsWith('/api/episodes')) {
            return mockApi(url);
          }
          return episodes(Number(new URLSearchParams(url.split('?')[1]).get('page') ?? '0'));
        }),
      );
    }

    function renderFeed() {
      render(
        <MemoryRouter initialEntries={['/']}>
          <PlayerProvider>
            <EpisodeFeed />
          </PlayerProvider>
        </MemoryRouter>,
      );
    }

    it('says it could not load, rather than that there is nothing', async () => {
      stubEpisodes(() => serverError());
      renderFeed();

      expect(await screen.findByText('Could not load episodes.')).toBeInTheDocument();
      // "No episodes yet" would be a false statement about the show, not about the request.
      expect(screen.queryByText('No episodes yet.')).not.toBeInTheDocument();
    });

    it('says there is nothing when there is nothing', async () => {
      stubEpisodes(() => pageOf([], 0, 0));
      renderFeed();

      expect(await screen.findByText('No episodes yet.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
    });

    it('appends the next page and stops offering more after the last', async () => {
      stubEpisodes((page) => (page === 0 ? pageOf([card('a', 'First')], 0, 2) : pageOf([card('b', 'Second')], 1, 2)));
      renderFeed();

      fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));

      expect(await screen.findByRole('link', { name: 'Second' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'First' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
    });

    it('does not retry a failed page on its own while the sentinel stays in view', async () => {
      // A real observer reports a sentinel that is already visible as soon as it observes it, and the list
      // rebuilds its observer on every settle — so a failing page was fetched about sixty times a second.
      class InViewObserver {
        constructor(private readonly callback: IntersectionObserverCallback) {}
        observe() {
          // A macrotask, not a microtask: on a regression the storm then stays interruptible, so this
          // test fails on the count instead of hanging the event loop.
          setTimeout(() => this.callback([{ isIntersecting: true } as IntersectionObserverEntry], this as never));
        }
        disconnect() {}
        unobserve() {}
        takeRecords() {
          return [];
        }
      }
      vi.stubGlobal('IntersectionObserver', InViewObserver);
      let laterPageRequests = 0;
      stubEpisodes((page) => {
        if (page === 0) return pageOf([card('a', 'First')], 0, 2);
        laterPageRequests++;
        return serverError();
      });
      renderFeed();

      expect(await screen.findByText('Could not load more episodes. Try again.')).toBeInTheDocument();
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 100));
      });
      expect(laterPageRequests).toBe(1);
    });

    it('keeps what it has when a later page fails, says so, and lets the button retry', async () => {
      let secondPageWorks = false;
      stubEpisodes((page) => {
        if (page === 0) return pageOf([card('a', 'First')], 0, 2);
        return secondPageWorks ? pageOf([card('b', 'Second')], 1, 2) : serverError();
      });
      renderFeed();

      fireEvent.click(await screen.findByRole('button', { name: 'Load more' }));
      expect(await screen.findByText('Could not load more episodes. Try again.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'First' })).toBeInTheDocument();

      secondPageWorks = true;
      fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
      expect(await screen.findByRole('link', { name: 'Second' })).toBeInTheDocument();
      expect(screen.queryByText('Could not load more episodes. Try again.')).not.toBeInTheDocument();
    });
  });
});
