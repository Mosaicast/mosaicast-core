// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { AdminSiteAccessView } from '../../api/types';
import { AdminAccess } from './AdminAccess';

function viewOf(patch: Partial<AdminSiteAccessView['access']> = {}, edge: AdminSiteAccessView['edge']['status'] = 'unknown'): AdminSiteAccessView {
  return {
    access: {
      state: 'closed',
      effectiveState: 'closed',
      opensAt: null,
      endsAt: null,
      passFloor: 'podcaster',
      staffLogin: { visible: false, providers: [] },
      now: '2026-10-11T12:00:00Z',
      revision: 3,
      ...patch,
    },
    signupsWhileClosed: false,
    updatedAt: null,
    edge: { status: edge, lastSeen: edge === 'connected' ? '2026-10-11T12:00:00Z' : null, build: 'abc123' },
  };
}

let current = viewOf();
const put = vi.fn(async (_path: string, _body: unknown) => current);
const post = vi.fn(async (_path: string) => viewOf({ state: 'scheduled', effectiveState: 'scheduled', opensAt: '2026-10-11T12:00:12Z' }));

vi.mock('../../api/client', () => ({
  api: {
    get: vi.fn(async () => current),
    put: (path: string, body: unknown) => put(path, body),
    post: (path: string) => post(path),
  },
}));

describe('AdminAccess', () => {
  beforeEach(() => {
    current = viewOf();
    put.mockClear();
    post.mockClear();
  });

  it('says plainly when no edge enforces the settings', async () => {
    render(<AdminAccess />);

    // A "closed" setting nothing enforces is a site that is open while its admin believes otherwise.
    expect(await screen.findByText(/No edge is connected/)).toBeInTheDocument();
  });

  it('names the edge and its build when it reports in', async () => {
    current = viewOf({}, 'connected');
    render(<AdminAccess />);

    expect(await screen.findByText(/enforcing these settings.*abc123/)).toBeInTheDocument();
  });

  it('sends a scheduled opening as an instant and drops times other states do not use', async () => {
    render(<AdminAccess />);
    fireEvent.click(await screen.findByRole('radio', { name: /Countdown/ }));
    fireEvent.change(screen.getByLabelText(/Opens at/), { target: { value: '2026-10-18T12:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(put).toHaveBeenCalledWith('/api/admin/site/access', {
      state: 'scheduled',
      opensAt: new Date('2026-10-18T12:00').toISOString(),
      endsAt: null,
      passFloor: 'podcaster',
      staffLoginVisible: false,
      signupsWhileClosed: false,
      edgeCounting: true,
    });
  });

  it('asks before opening now, then opens', async () => {
    render(<AdminAccess />);
    fireEvent.click(await screen.findByRole('button', { name: 'Open now' }));

    expect(post).not.toHaveBeenCalled();
    expect(screen.getByText(/opens in about 12 seconds/)).toBeInTheDocument();
    const confirms = screen.getAllByRole('button', { name: 'Open now' });
    fireEvent.click(confirms[confirms.length - 1]);

    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/admin/site/access/open-now'));
  });

  it('offers no open-now on a site that is already open', async () => {
    current = viewOf({ state: 'open', effectiveState: 'open' });
    render(<AdminAccess />);
    await screen.findByText(/open to everyone/);

    expect(screen.queryByRole('button', { name: 'Open now' })).toBeNull();
  });
});
