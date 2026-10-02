// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminPlanned } from './AdminPlanned';

const FEED = { id: 'f1', slug: 'the-cast', title: 'The Cast' };
const PLAN = {
  id: 'p1', slug: 'the-cast-s02e05', url: '/episodes/the-cast-s02e05', feedId: 'f1', feedSlug: 'the-cast',
  feedTitle: 'The Cast', season: 2, episodeNo: 5, title: 'The interview', description: '', phase: 'PLANNED',
  announceAt: null, clientRef: null, createdAt: '2026-10-01T10:00:00Z',
};

type Call = { url: string; method: string; body: unknown };

function stub(overrides: Record<string, () => Promise<unknown>> = {}) {
  const calls: Call[] = [];
  const ok = (data: unknown) =>
    Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(data)) });
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      const key = `${method} ${url}`;
      if (overrides[key]) return overrides[key]();
      if (url === '/api/admin/feeds') return ok([FEED]);
      if (url === '/api/admin/episodes/planned') return ok([PLAN]);
      if (url.endsWith('/match-candidates')) {
        return ok([
          { slug: 'imported-40', title: 'A conversation', season: 2, episodeNo: 40, publishedAt: null, hasPluginData: false },
          { slug: 'imported-41', title: 'Quiz night', season: 2, episodeNo: 41, publishedAt: null, hasPluginData: true },
        ]);
      }
      return ok({ ...PLAN, slug: 'new-slug', title: 'New one' });
    }),
  );
  return calls;
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AdminPlanned />
    </MemoryRouter>,
  );
}

describe('AdminPlanned (core#252)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('plans quietly by default, and sends a scheduled time as an instant', async () => {
    const calls = stub();
    renderPage();
    fireEvent.change(await screen.findByLabelText('Title'), { target: { value: 'Season finale' } });
    fireEvent.click(screen.getByRole('button', { name: 'Plan episode' }));
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    const quiet = calls.find((c) => c.method === 'POST')!;
    expect(quiet.url).toBe('/api/admin/feeds/f1/planned-episodes');
    expect(quiet.body).toMatchObject({ title: 'Season finale', announceAt: null });

    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Christmas special' } });
    fireEvent.click(screen.getByLabelText(/Everyone, from a set time/));
    fireEvent.change(screen.getByLabelText('Public from'), { target: { value: '2026-12-24T18:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Plan episode' }));
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2));
    const scheduled = calls.filter((c) => c.method === 'POST')[1].body as { announceAt: string };
    expect(scheduled.announceAt).toBe(new Date('2026-12-24T18:00').toISOString());
  });

  it('offers only matchable episodes and explains a refused match in the admin\'s language', async () => {
    const refusal = () =>
      Promise.resolve({
        ok: false,
        status: 400,
        statusText: '',
        json: () => Promise.resolve({ detail: 'english detail', code: 'planned.match.targetHasPluginData' }),
      });
    stub({ 'POST /api/admin/episodes/the-cast-s02e05/match': refusal });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Match…' }));

    // The candidate that already carries plugin data cannot be picked.
    expect(await screen.findByLabelText(/Quiz night/)).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/A conversation/));
    fireEvent.click(screen.getByRole('button', { name: 'Match' }));

    expect(await screen.findByText(/already has plugin data of its own/)).toBeInTheDocument();
  });
});
