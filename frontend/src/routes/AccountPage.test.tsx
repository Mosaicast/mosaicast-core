// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { AccountPage } from './AccountPage';

const refresh = vi.fn();

vi.mock('../auth/UserContext', () => ({
  useUser: () => ({
    user: { id: 'me', displayName: 'Dev Fan', avatarUrl: null, role: 'fan' },
    refresh,
  }),
}));

vi.mock('../consent/ProgressPreference', () => ({ ProgressPreference: () => null }));

/**
 * Stubs the endpoints the page loads, letting one of them fail with an RFC 7807 body.
 *
 * @param problem the problem type PATCH /api/me should answer with, or null for success
 */
function stubFetch(problem: string | null = null) {
  const calls: Array<{ url: string; method: string; body?: string }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: init?.body as string | undefined });
      if (method === 'PATCH' && problem) {
        // The client reads a failure with `json()`, not `text()` — a stub that only offers the latter
        // makes the problem body invisible and every refusal collapse into the generic message.
        return Promise.resolve({
          ok: false,
          status: 409,
          statusText: 'Conflict',
          headers: { get: () => 'application/problem+json' },
          json: () =>
            Promise.resolve({ type: `https://mosaicast.dev/problems/${problem}`, detail: 'nope' }),
        });
      }
      const body = method === 'PATCH' ? { id: 'me', displayName: 'New Name', role: 'fan' } : [];
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

afterEach(() => {
  vi.unstubAllGlobals();
  refresh.mockClear();
});

describe('AccountPage display name (ARCHITECTURE §8.6)', () => {
  it('prefills the field with the current name and disables saving until it changes', async () => {
    stubFetch();
    render(<AccountPage />);

    const field = await screen.findByLabelText('Your name');
    expect(field).toHaveValue('Dev Fan');
    expect(screen.getByRole('button', { name: 'Save name' })).toBeDisabled();
  });

  it('sends the new name and refreshes the profile', async () => {
    const calls = stubFetch();
    render(<AccountPage />);

    fireEvent.change(await screen.findByLabelText('Your name'), { target: { value: 'Captain Maritime' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    await waitFor(() => {
      const patch = calls.find((c) => c.method === 'PATCH');
      expect(patch?.url).toBe('/api/me');
      expect(patch?.body).toBe(JSON.stringify({ displayName: 'Captain Maritime' }));
    });
    expect(refresh).toHaveBeenCalled();
  });

  it.each([
    ['display-name-taken', 'Somebody else already uses that name.'],
    ['display-name-locked', 'You have changed your name recently. Please try again later.'],
    ['display-name-refused', 'That name is not available. Please pick another.'],
    ['display-name-invalid', 'Names must be between 3 and 32 characters.'],
  ])('translates the %s refusal from the problem type', async (problem, message) => {
    // The whole point of typing the refusals: the UI must not have to match the server's English.
    stubFetch(problem);
    render(<AccountPage />);

    fireEvent.change(await screen.findByLabelText('Your name'), { target: { value: 'Something Else' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save name' }));

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });

  it('says the name is public, because a leaderboard is a bad place to find that out', async () => {
    stubFetch();
    render(<AccountPage />);
    expect(await screen.findByText(/name other people on this site see/i)).toBeInTheDocument();
  });
});
