// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { AppLogEntry, HealthView } from '../../api/types';
import { AdminLogs } from './AdminLogs';

const ENTRY: AppLogEntry = {
  id: 1,
  at: '2026-07-27T10:15:30Z',
  level: 'WARN',
  subsystem: 'plugin',
  source: 'PluginLoaderService',
  pluginId: 'acme',
  message: "Rejected plugin 'acme': platformApi 0.3.0 is incompatible with host 0.4.0",
  detail: 'dev.mosaicast.core.plugin.PluginValidationException: …',
  context: { feedId: 'feed-7' },
};

const HEALTH: HealthView = {
  version: '0.5.7',
  uptimeSeconds: 1234,
  plugins: [{ id: 'acme', name: 'Acme', status: 'REJECTED', enabled: true, reason: 'platformApi mismatch' }],
  feeds: [],
  counts: [{ subsystem: 'plugin', level: 'WARN', count: 1 }],
  countsSince: '2026-07-26T10:15:30Z',
};

/** Records every request and answers logs / facets / health. */
function stubFetch(entries = [ENTRY]) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      calls.push(url);
      let payload: unknown = {};
      if (url.startsWith('/api/admin/logs/facets')) {
        payload = { subsystems: ['plugin', 'feed'], pluginIds: ['acme'] };
      } else if (url.startsWith('/api/admin/logs')) {
        payload = { items: entries, page: 0, size: 50, totalElements: entries.length, totalPages: 1 };
      } else if (url.startsWith('/api/admin/health')) {
        payload = HEALTH;
      }
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(payload)) });
    }),
  );
  return calls;
}

describe('AdminLogs', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('lists entries in aligned columns', async () => {
    stubFetch();
    render(<AdminLogs />);

    expect(await screen.findByText(/Rejected plugin 'acme'/)).toBeInTheDocument();
    // "WARN" is also a filter option, so assert the row's chip rather than any matching text.
    expect(screen.getAllByText('WARN').some((el) => el.className.includes('mc-chip--warn'))).toBe(true);
    // Subsystem and origin are their own cells now, not one run-on string that pushes the message around.
    const row = screen.getByText(/Rejected plugin 'acme'/).closest('tr');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('plugin')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('acme')).toBeInTheDocument();
  });

  it('offers an expand affordance only on rows that have detail', async () => {
    stubFetch([ENTRY, { ...ENTRY, id: 2, message: 'nothing to expand', detail: null, context: null }]);
    render(<AdminLogs />);

    await screen.findByText(/Rejected plugin 'acme'/);
    // One caret, on the row that actually carries something — nobody should have to click to find out.
    const toggles = screen.getAllByRole('button', { name: 'Show detail' });
    expect(toggles).toHaveLength(1);

    const plainRow = screen.getByText('nothing to expand').closest('tr');
    expect(plainRow?.className).not.toContain('expandable');
  });

  it('shows the rejection reason on the health card', async () => {
    stubFetch();
    render(<AdminLogs />);

    expect(await screen.findByText('Something needs attention.')).toBeInTheDocument();
    expect(screen.getByText('platformApi mismatch')).toBeInTheDocument();
  });

  it('opens at WARN and above rather than showing everything stored', async () => {
    // The store keeps INFO (DEBUG in dev); the viewer starts where an operator wants to look, one dropdown
    // away from the rest.
    const calls = stubFetch();
    render(<AdminLogs />);

    await waitFor(() => expect(calls.some((url) => url.includes('level=WARN'))).toBe(true));
    expect((screen.getByLabelText('Level') as HTMLSelectElement).value).toBe('WARN');
  });

  it('turns filters into query parameters', async () => {
    const calls = stubFetch();
    render(<AdminLogs />);
    await screen.findByText(/Rejected plugin/);

    fireEvent.change(screen.getByLabelText('Level'), { target: { value: 'INFO' } });

    await waitFor(() => {
      expect(calls.some((url) => url.includes('level=INFO') && url.includes('page=0'))).toBe(true);
    });
  });

  it('reveals detail and context only when a row is opened', async () => {
    stubFetch();
    render(<AdminLogs />);
    const row = await screen.findByText(/Rejected plugin 'acme'/);

    expect(screen.queryByText(/PluginValidationException/)).not.toBeInTheDocument();
    fireEvent.click(row);
    expect(screen.getByText(/PluginValidationException/)).toBeInTheDocument();
    expect(screen.getByText(/feed-7/)).toBeInTheDocument();
  });

  it('polls only while auto-refresh is on', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const calls = stubFetch();
    render(<AdminLogs />);
    await vi.waitFor(() => expect(calls.some((u) => u.startsWith('/api/admin/logs?'))).toBe(true));

    const before = calls.length;
    vi.advanceTimersByTime(11_000);
    expect(calls.length).toBe(before); // off by default — no background traffic

    fireEvent.click(screen.getByLabelText('Auto-refresh'));
    vi.advanceTimersByTime(11_000);
    await vi.waitFor(() => expect(calls.length).toBeGreaterThan(before));
  });
});
