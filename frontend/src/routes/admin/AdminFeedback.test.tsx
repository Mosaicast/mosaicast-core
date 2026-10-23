// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminFeedback } from './AdminFeedback';

const role = vi.hoisted(() => ({ value: 'admin' }));
vi.mock('../../auth/UserContext', () => ({
  useUser: () => ({ user: { id: 'a', displayName: 'Admin', role: role.value }, refresh: () => {} }),
}));
vi.mock('../../api/MetaContext', () => ({ useMeta: () => null, useRefreshMeta: () => () => {} }));

const anonymous = {
  id: 'r1',
  kind: 'problem',
  message: 'The player stutters on episode one',
  context: { route: '/episodes/one', error: 'load-failed', core: '0.11.0' },
  status: 'new',
  createdAt: '2026-10-23T10:00:00Z',
  locale: 'de',
  contact: 'me@example.org',
  sender: null,
  repliedAt: null,
  handledAt: null,
};
const signedIn = {
  ...anonymous,
  id: 'r2',
  kind: 'idea',
  message: 'Dark mode please',
  contact: null,
  sender: { id: 'u1', displayName: 'Dev Fan', blocked: false },
};

function stubFetch(listStatus = 200) {
  const calls: { url: string; method: string; body?: unknown }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body ? JSON.parse(init.body as string) : undefined });
      let body: unknown = {};
      if (url.startsWith('/api/admin/feedback/settings')) {
        body = { mode: 'everyone', url: null, floor: 'admin' };
      } else if (url.startsWith('/api/admin/feedback?')) {
        if (listStatus !== 200) {
          return Promise.resolve({
            ok: false,
            status: listStatus,
            statusText: '',
            headers: { get: () => null },
            json: () => Promise.resolve({ type: 'https://mosaicast.dev/problems/forbidden' }),
          });
        }
        body = { items: [anonymous, signedIn], page: 0, size: 50, totalElements: 2, totalPages: 1 };
      } else if (url === '/api/admin/feedback/r1') {
        body = { ...anonymous, status: 'seen' };
      } else if (url === '/api/admin/feedback/status') {
        body = { changed: 2 };
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: () => Promise.resolve(JSON.stringify(body)),
      });
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  role.value = 'admin';
});

describe('AdminFeedback', () => {
  it('lists reports and opens one with its context and contact', async () => {
    stubFetch();
    render(<AdminFeedback />);
    const row = (await screen.findByText('The player stutters on episode one')).closest('li') as HTMLElement;
    expect(within(row).getByText('Not signed in', { exact: false })).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { expanded: false }));
    await within(row).findByText('Page: /episodes/one');
    expect(within(row).getByText('me@example.org')).toBeTruthy();
    expect(within(row).getByRole('link', { name: 'Answer by email' }).getAttribute('href')).toBe(
      'mailto:me@example.org',
    );
    // Opening it marked it seen.
    expect(within(row).getByText('Seen')).toBeTruthy();
  });

  it('marks a selection as spam in one go', async () => {
    const calls = stubFetch();
    render(<AdminFeedback />);
    await screen.findByText('Dark mode please');
    screen.getAllByRole('checkbox', { name: 'Select' }).forEach((box) => fireEvent.click(box));
    fireEvent.click(screen.getByRole('button', { name: 'Mark 2 as spam' }));
    await waitFor(() =>
      expect(calls.find((c) => c.url === '/api/admin/feedback/status')?.body).toEqual({
        status: 'spam',
        ids: ['r1', 'r2'],
      }),
    );
  });

  it('shows the settings to admins only', async () => {
    stubFetch();
    const { unmount } = render(<AdminFeedback />);
    expect(await screen.findByText('Who can send feedback')).toBeTruthy();
    unmount();
    role.value = 'podcaster';
    stubFetch();
    render(<AdminFeedback />);
    await screen.findByText('Dark mode please');
    expect(screen.queryByText('Who can send feedback')).toBeNull();
  });

  it('tells a reader below the floor they may not read it', async () => {
    role.value = 'podcaster';
    stubFetch(403);
    render(<AdminFeedback />);
    expect(await screen.findByText("Not allowed")).toBeTruthy();
    expect(screen.queryByText('Dark mode please')).toBeNull();
  });
});
