// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';
import './i18n';

/**
 * Shell smoke test (E4a): the chrome renders and routing lands on Home even with the API offline — the
 * site payload / catalog / meta calls all fail gracefully and the shell falls back to defaults.
 */
describe('App shell (E4a)', () => {
  beforeEach(() => {
    // API offline → every fetch rejects; components use their fallbacks.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the brand, primary nav and the home view', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );
    // Brand falls back to the app title when the site payload is unavailable.
    expect(screen.getByRole('link', { name: /Mosaicast/i })).toBeInTheDocument();
    // Home route rendered the site panel heading (site name falls back to the app title when offline).
    expect(await screen.findByRole('heading', { name: 'Mosaicast' })).toBeInTheDocument();
  });

  it('shows a 404 landmark for an unknown route', () => {
    render(
      <MemoryRouter initialEntries={['/nope']}>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'Not found' })).toBeInTheDocument();
  });
});

describe('App shell — logged in (E4c)', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        const ok = (data: unknown) =>
          Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(data) });
        if (url.startsWith('/api/me')) {
          return ok({ id: 'u1', displayName: 'Ada Admin', avatarUrl: null, role: 'admin' });
        }
        if (url.startsWith('/api/meta')) {
          return ok({ name: 'Mosaicast', version: 'test', devLoginEnabled: false });
        }
        if (url.startsWith('/api/site')) {
          return Promise.reject(new Error('no site'));
        }
        if (url.startsWith('/api/episodes')) {
          return ok({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
        }
        return ok([]); // /api/feeds, /api/tags
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('shows the account menu (name + Admin) instead of Log in', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );
    // Logged in → the account menu shows the user's name, and the "Log in" affordance is gone.
    expect(await screen.findByText('Ada Admin')).toBeInTheDocument();
    expect(screen.queryByText('Log in')).not.toBeInTheDocument();
  });
});
