// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminUsers } from './AdminUsers';

vi.mock('../../auth/UserContext', () => ({
  useUser: () => ({ user: { id: 'me', displayName: 'Me', avatarUrl: null, role: 'admin' } }),
}));

const USERS = [
  { id: 'me', displayName: 'Me', avatarUrl: null, role: 'admin', createdAt: '2026-01-01T00:00:00Z', identities: [] },
  {
    id: 'u2',
    displayName: 'Fan Bob',
    avatarUrl: null,
    role: 'fan',
    createdAt: '2026-02-01T00:00:00Z',
    identities: [{ provider: 'discord', email: 'bob@example.com' }],
  },
];

function stubFetch() {
  const calls: Array<{ url: string; method: string; body?: string }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(USERS)) });
    }),
  );
  return calls;
}

describe('AdminUsers', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lists users, disables the current admin, and PUTs a role change', async () => {
    const calls = stubFetch();
    render(<AdminUsers />);

    expect(await screen.findByText('Fan Bob')).toBeInTheDocument();
    const selects = screen.getAllByRole('combobox') as HTMLSelectElement[];
    // Two rows: the current user's select is disabled (no self role change), the other is editable.
    expect(selects.find((s) => s.value === 'admin')!.disabled).toBe(true);
    const fanSelect = selects.find((s) => s.value === 'fan')!;
    expect(fanSelect.disabled).toBe(false);

    fireEvent.change(fanSelect, { target: { value: 'podcaster' } });

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT');
      expect(put?.url).toBe('/api/admin/users/u2/role');
      expect(put?.body).toContain('podcaster');
    });
  });
});
