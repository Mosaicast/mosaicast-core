// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminFeeds } from './AdminFeeds';

const FEED = {
  id: 'f1',
  type: 'rss',
  url: 'https://example.com/feed.xml',
  title: 'Test Cast',
  enabled: true,
  pollIntervalSeconds: 1800,
  lastFetchedAt: null,
  lastFetchStatus: 'OK',
  lastError: null,
  consecutiveFailures: 0,
  episodeCount: 3,
};

function stubFetch() {
  const calls: Array<{ url: string; method: string }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET' });
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify([FEED])) });
    }),
  );
  return calls;
}

describe('AdminFeeds poll interval', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the current interval and POSTs a new one', async () => {
    const calls = stubFetch();
    render(<AdminFeeds />);

    expect(await screen.findByText('Test Cast')).toBeInTheDocument();
    const select = screen.getByRole('combobox') as HTMLSelectElement;
    expect(select.value).toBe('1800'); // 30 min preset selected

    fireEvent.change(select, { target: { value: '3600' } });

    await waitFor(() => {
      expect(calls.some((c) => c.method === 'POST' && c.url === '/api/admin/feeds/f1/poll-interval?seconds=3600')).toBe(
        true,
      );
    });
  });
});
