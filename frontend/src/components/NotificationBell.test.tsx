// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { NotificationBell } from './NotificationBell';

/** Stubs the two endpoints the bell uses; `items` is what the panel gets when it opens. */
function stubFetch(unread: number, items: unknown[] = [], totalElements?: number) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      const body = url.includes('unread-count')
        ? { unread }
        : {
            items,
            page: 0,
            size: 10,
            totalElements: totalElements ?? items.length,
            totalPages: 1,
          };
      return Promise.resolve({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        text: () => Promise.resolve(JSON.stringify(body)),
      });
    }),
  );
  return calls;
}

const view = (over: Record<string, unknown>) => ({
  id: 'n1',
  source: 'system',
  kind: null,
  payload: {},
  link: null,
  createdAt: '2026-09-01T10:00:00Z',
  readAt: null,
  ...over,
});

function renderBell() {
  return render(
    <MemoryRouter>
      <NotificationBell />
    </MemoryRouter>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe('NotificationBell (ARCHITECTURE §17)', () => {
  it('shows the unread count and does not fetch the list until opened', async () => {
    const calls = stubFetch(3);
    renderBell();

    await screen.findByText('3');
    // Most page loads never open the panel; fetching the list on mount would buy latency nobody spends.
    expect(calls.some((c) => c.includes('/api/me/notifications?'))).toBe(false);

    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(calls.some((c) => c.includes('/api/me/notifications?'))).toBe(true));
  });

  it('translates a system kind rather than printing text from the server', async () => {
    // The shell owns the wording, which is what stops an admin authoring the message about a name they
    // were not allowed to choose (§8.6.1).
    stubFetch(1, [
      view({ kind: 'name-reverted', payload: { previous: 'Something Rude', current: 'Dev Fan' } }),
    ]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    expect(await screen.findByText(/changed your display name back to/i)).toBeInTheDocument();
  });

  it('renders an admin warning as the text it is', async () => {
    stubFetch(1, [view({ source: 'admin', payload: { text: 'Please keep it civil.' } })]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    expect(await screen.findByText('Please keep it civil.')).toBeInTheDocument();
  });

  it('picks the reader’s language from a plugin message, falling back to English', async () => {
    // §17.1: the plugin sent every language it had, because it could not know which one the reader would
    // have days later. The shell is the only place that knows.
    stubFetch(2, [
      view({ id: 'n1', source: 'plugin:bingo', payload: { en: 'Bingo resolved', de: 'Bingo aufgelöst' } }),
      view({ id: 'n2', source: 'plugin:bingo', payload: { en: 'English only' } }),
    ]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    // The test i18n instance runs in English, so the English entry is chosen and the German ignored.
    expect(await screen.findByText('Bingo resolved')).toBeInTheDocument();
    expect(screen.getByText('English only')).toBeInTheDocument();
    expect(screen.queryByText('Bingo aufgelöst')).not.toBeInTheDocument();
  });

  it('never renders a notification as markup', async () => {
    stubFetch(1, [view({ source: 'admin', payload: { text: '<img src=x onerror=alert(1)>' } })]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    // Text, never HTML (§17.1) — it arrives as the characters that were typed.
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('shows the whole unread count rather than clamping it', async () => {
    // A number that stops counting stops being information, and this is the one place the size of a
    // backlog is visible at a glance.
    stubFetch(137);
    renderBell();
    expect(await screen.findByText('137')).toBeInTheDocument();
  });

  it('offers the ones it did not show, not the total', async () => {
    // A reader deciding whether to open the full page wants to know what they have *not* seen.
    stubFetch(30, [view({}), view({ id: 'n2' })], 12);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    const more = await screen.findByText('10 more');
    expect(more).toHaveAttribute('href', '/notifications');
  });

  it('marks one read only when the reader says so', async () => {
    // Explicitly not on scroll or on render: a glance at a bell is not having read a warning, and read
    // state is what an admin later relies on (§17).
    const calls = stubFetch(1, [view({ source: 'admin', payload: { text: 'Please keep it civil.' } })]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    await screen.findByText('Please keep it civil.');
    expect(calls.some((c) => c.includes('/read'))).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Mark as read' }));
    await waitFor(() => expect(calls.some((c) => c.endsWith('/api/me/notifications/n1/read'))).toBe(true));
  });

  it('stays open after marking one read', async () => {
    // The panel is a list you work through: closing it on the first tick means reopening it for every
    // notification, and losing your place each time.
    stubFetch(2, [
      view({ id: 'n1', source: 'admin', payload: { text: 'first' } }),
      view({ id: 'n2', source: 'admin', payload: { text: 'second' } }),
    ]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    await screen.findByText('first');
    fireEvent.click(screen.getAllByRole('button', { name: 'Mark as read' })[0]);

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Mark as read' })).toHaveLength(1));
    // Still there — and so is the one that has not been read yet.
    expect(screen.getByText('first')).toBeInTheDocument();
    expect(screen.getByText('second')).toBeInTheDocument();
  });

  it('stays open when marking everything read', async () => {
    stubFetch(1, [view({ source: 'admin', payload: { text: 'warned' } })]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    fireEvent.click(await screen.findByRole('button', { name: 'Mark all read' }));

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Mark all read' })).toBeNull());
    expect(screen.getByText('warned')).toBeInTheDocument();
  });

  it('offers no way to delete a notification', async () => {
    // Retention clears read ones on its own (§17.2), and a user who could delete an admin warning would
    // erase the record that they received it.
    stubFetch(1, [view({ source: 'admin', payload: { text: 'warned' } })]);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    await screen.findByText('warned');
    expect(screen.queryByRole('button', { name: /delete|remove|dismiss/i })).toBeNull();
  });

  it('says so when there is nothing to read', async () => {
    stubFetch(0);
    renderBell();

    fireEvent.click(await screen.findByRole('button'));
    expect(await screen.findByText('Nothing to catch up on.')).toBeInTheDocument();
    // No count badge at zero: a bell that always carries a number has nothing left to say with one.
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });
});
