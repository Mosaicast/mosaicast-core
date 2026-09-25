// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { api } from '../api/client';
import { UserProvider, useUser } from './UserContext';

function Who() {
  const { user, loading } = useUser();
  return <output data-testid="who">{loading ? 'loading' : (user?.displayName ?? 'anonymous')}</output>;
}

const response = (status: number, body?: unknown) =>
  Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    json: () => (body === undefined ? Promise.reject(new Error('no body')) : Promise.resolve(body)),
    text: () => Promise.resolve(body === undefined ? '' : JSON.stringify(body)),
  });

describe('UserProvider', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('falls back to anonymous once when the session dies mid-visit (core#185)', async () => {
    // The session expires (or is signed out in another tab): every later call fails on its own, and the
    // header used to keep showing a name over a page that could no longer load anything.
    let sessionAlive = true;
    const fetchMock = vi.fn((url: string) => {
      if (url === '/api/me') {
        return sessionAlive ? response(200, { id: 'u1', displayName: 'Ada', role: 'FAN' }) : response(401);
      }
      return response(401);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(
      <UserProvider>
        <Who />
      </UserProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('Ada'));

    sessionAlive = false;
    // A page of mounts meeting the dead session at once: one re-check, not one per failure.
    await act(async () => {
      await Promise.allSettled([1, 2, 3].map(() => api.get('/api/me/notifications')));
    });

    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('anonymous'));
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/me')).toHaveLength(2);
  });
});
