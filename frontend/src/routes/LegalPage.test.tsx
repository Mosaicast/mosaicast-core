// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { LegalPage } from './LegalPage';

function mockPage(html: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        text: () =>
          Promise.resolve(JSON.stringify({ slug: 'privacy', title: 'Privacy Policy', role: 'privacy', html })),
      }),
    ),
  );
}

describe('LegalPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders the sanitized HTML body of a legal page', async () => {
    mockPage('<h2>Cookies and local storage</h2><p>Strictly necessary only.</p>');
    render(
      <MemoryRouter initialEntries={['/legal/privacy']}>
        <Routes>
          <Route path="/legal/:slug" element={<LegalPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole('heading', { name: 'Privacy Policy' })).toBeInTheDocument();
    expect(await screen.findByText('Cookies and local storage')).toBeInTheDocument();
    expect(screen.getByText('Strictly necessary only.')).toBeInTheDocument();
  });
});
