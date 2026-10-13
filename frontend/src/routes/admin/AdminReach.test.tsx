// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { ReachView } from '../../api/types';
import { AdminReach } from './AdminReach';

const hour = (h: number) => new Date(Date.UTC(2026, 9, 18, h)).toISOString();

const view = (patch: Partial<ReachView> = {}): ReachView => ({
  counting: true,
  from: hour(0),
  to: hour(24 * 7),
  granularity: 'day',
  series: [
    { bucket: hour(0), source: 'core', kind: 'view', count: 120 },
    { bucket: hour(0), source: 'core', kind: 'play', count: 30 },
    { bucket: hour(24), source: 'edge', kind: 'view', count: 5000 },
  ],
  top: [
    { subject: 'episode/s05-special', kind: 'play', milestone: 0, count: 25 },
    { subject: 'home', kind: 'view', milestone: 0, count: 80 },
  ],
  edgeViews: [{ subject: 'edge/countdown', kind: 'view', milestone: 0, count: 5000 }],
  edge: {
    counting: true,
    waiting: 2500,
    waitingCapped: true,
    waitingAt: hour(30),
    page: 'scheduled',
    peakToday: 2500,
    peakThisPolicy: 2500,
  },
  ...patch,
});

let current = view();
let role = 'admin';
const put = vi.fn(async (_path: string, _body: unknown) => ({ counting: false }));

vi.mock('../../api/client', () => ({
  api: { get: vi.fn(async () => current), put: (path: string, body: unknown) => put(path, body) },
}));
vi.mock('../../auth/UserContext', () => ({ useUser: () => ({ user: { role } }) }));

describe('AdminReach', () => {
  beforeEach(() => {
    current = view();
    role = 'admin';
    put.mockClear();
  });

  it('shows totals, the most played episode and the edge gauge with its cap', async () => {
    render(<AdminReach />);

    expect(await screen.findByText('s05-special')).toBeInTheDocument();
    expect(screen.getByText('120')).toBeInTheDocument();
    expect(screen.getByText(/2,500\+ visitors on the countdown/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Page views, plays/ })).toBeInTheDocument();
  });

  it('says plainly what the numbers are not', async () => {
    render(<AdminReach />);

    expect(await screen.findByText(/no unique visitors, sessions or returning visitors/)).toBeInTheDocument();
  });

  it('lets an admin switch counting off', async () => {
    render(<AdminReach />);
    fireEvent.click(await screen.findByRole('checkbox', { name: /Count anonymously/ }));

    await waitFor(() => expect(put).toHaveBeenCalledWith('/api/admin/metrics/settings', { counting: false }));
    expect(await screen.findByText(/Counting is switched off/)).toBeInTheDocument();
  });

  it('shows a podcaster the numbers but not the switch', async () => {
    role = 'podcaster';
    render(<AdminReach />);

    expect(await screen.findByText('s05-special')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Count anonymously/ })).toBeNull();
  });
});
