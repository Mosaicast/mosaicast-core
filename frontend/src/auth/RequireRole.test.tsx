// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import type { Role } from '../api/types';
import { RequireRole } from './RequireRole';
import { UserProvider } from './UserContext';

function mockUser(role: Role) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        text: () => Promise.resolve(JSON.stringify({ id: 'u1', displayName: 'U', avatarUrl: null, role })),
      }),
    ),
  );
}

function renderGuarded(roles: Role[]) {
  return render(
    <MemoryRouter>
      <UserProvider>
        <RequireRole roles={roles}>
          <div>secret area</div>
        </RequireRole>
      </UserProvider>
    </MemoryRouter>,
  );
}

describe('RequireRole', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('renders children for an allowed role', async () => {
    mockUser('admin');
    renderGuarded(['admin']);
    expect(await screen.findByText('secret area')).toBeInTheDocument();
  });

  it('blocks a disallowed role with a 403 note', async () => {
    mockUser('fan');
    renderGuarded(['admin']);
    expect(await screen.findByText('Not allowed')).toBeInTheDocument();
    expect(screen.queryByText('secret area')).not.toBeInTheDocument();
  });

  it('tells an anonymous visitor to sign in, and stays on the page (#199)', async () => {
    // It used to redirect to the front page without a word, while a fan got an explanation.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: false, status: 401, statusText: '', json: () => Promise.reject(new Error()) })),
    );
    renderGuarded(['admin']);

    expect(await screen.findByText('Sign in to see this page')).toBeInTheDocument();
    expect(screen.queryByText('secret area')).not.toBeInTheDocument();
  });
});
