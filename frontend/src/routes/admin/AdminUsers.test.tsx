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

/** Unfinished erasures, as `/api/admin/erasures` reports them; empty is the normal state. */
const NO_ERASURES: unknown[] = [];

function stubFetch(erasures: unknown[] = NO_ERASURES) {
  const calls: Array<{ url: string; method: string; body?: string }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? 'GET', body: init?.body as string | undefined });
      const body = url.includes('/erasures')
        ? erasures
        : { items: USERS, page: 0, size: 50, totalElements: USERS.length, totalPages: 1 };
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
    }),
  );
  return calls;
}

describe('AdminUsers', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows an unfinished account erasure by plugin and reason', async () => {
    stubFetch([
      { id: 'x1', userId: 'u9', pluginId: 'bingo', status: 'PENDING', attempts: 1, lastError: 'plugin is not active' },
    ]);
    render(<AdminUsers />);

    // The plugin and the reason, not the person: their account is already gone, and re-attaching a name to
    // it in an admin list would undo part of what the deletion was for (§12).
    expect(await screen.findByText('bingo')).toBeInTheDocument();
    expect(screen.getByText(/plugin is not active/)).toBeInTheDocument();
    expect(screen.queryByText('u9')).not.toBeInTheDocument();

  });

  it('says nothing at all when no erasure is outstanding', async () => {
    stubFetch();
    render(<AdminUsers />);

    // The empty state is silence: a heading with nothing under it would read as a surface to check.
    expect(await screen.findByText('Fan Bob')).toBeInTheDocument();
    expect(screen.queryByText(/Unfinished account erasures/i)).not.toBeInTheDocument();
  });

  it('reverts a name without ever choosing one (ARCHITECTURE §8.6.1)', async () => {
    const calls = stubFetch();
    vi.stubGlobal('confirm', vi.fn(() => true));
    render(<AdminUsers />);

    await screen.findByText('Fan Bob');
    fireEvent.click(screen.getAllByRole('button', { name: 'Revert name' })[1]);

    await waitFor(() => {
      const post = calls.find((c) => c.method === 'POST' && c.url.includes('/name/revert'));
      expect(post?.url).toBe('/api/admin/users/u2/name/revert');
      // No body: the admin does not get to say what the name becomes, which is the whole point.
      expect(post?.body).toBeUndefined();
    });
  });

  it('offers no way to set a name, only to revert one', async () => {
    stubFetch();
    render(<AdminUsers />);

    await screen.findByText('Fan Bob');
    // A text input per row would mean an admin could type someone else's name for them (§8.6.1).
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Revert name' })).toHaveLength(2);
  });

  it('searches on submit rather than on every keystroke', async () => {
    const calls = stubFetch();
    render(<AdminUsers />);
    await screen.findByText('Fan Bob');
    const before = calls.filter((c) => c.url.startsWith('/api/admin/users?')).length;

    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'harbour' } });
    expect(calls.filter((c) => c.url.startsWith('/api/admin/users?'))).toHaveLength(before);

    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => {
      expect(calls.some((c) => c.url.includes('q=harbour'))).toBe(true);
    });
  });

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
