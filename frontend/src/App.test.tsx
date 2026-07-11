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
    // Home route rendered its heading (the unified episode feed).
    expect(await screen.findByRole('heading', { name: 'Episodes' })).toBeInTheDocument();
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
