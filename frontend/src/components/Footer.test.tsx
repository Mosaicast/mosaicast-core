// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { Footer } from './Footer';

vi.mock('../hooks/useLegalEntries', () => ({
  useLegalEntries: () => [
    { slug: 'imprint', title: 'Imprint', role: 'imprint' },
    { slug: 'privacy', title: 'Privacy Policy', role: 'privacy' },
  ],
}));
vi.mock('../theme/SiteContext', () => ({
  useSite: () => ({ site: { name: 'My Cast' }, mode: 'light', refresh: vi.fn() }),
}));

describe('Footer', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders the legal links resolved for the locale', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('{"version":"9.9.9"}') })),
    );
    render(
      <MemoryRouter>
        <Footer />
      </MemoryRouter>,
    );
    const imprint = screen.getByRole('link', { name: 'Imprint' });
    expect(imprint).toHaveAttribute('href', '/legal/imprint');
    expect(screen.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/legal/privacy');
  });
});
