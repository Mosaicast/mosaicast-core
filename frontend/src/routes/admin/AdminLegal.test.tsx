// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AdminLegal } from './AdminLegal';

const PAGE = { slug: 'privacy', roleMarker: 'privacy', sortOrder: 20, translations: [] };

/** GET lists one page; POST answers with the given status (and problem+json body when failing). */
function stubFetch(postStatus: number, problem?: Record<string, unknown>) {
  const calls: Array<{ url: string; method: string }> = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method });
      if (method === 'POST') {
        return Promise.resolve({
          ok: postStatus < 400,
          status: postStatus,
          json: () => Promise.resolve(problem ?? {}),
          text: () => Promise.resolve(''),
        });
      }
      return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify([PAGE])) });
    }),
  );
  return calls;
}

describe('AdminLegal', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the server reason when creating a page is rejected', async () => {
    // Regression: the rejected promise was never caught, so a duplicate slug looked like a dead button.
    stubFetch(409, { detail: "A legal page with slug 'privacy' already exists" });
    render(<AdminLegal />);

    fireEvent.change(await screen.findByPlaceholderText('New page slug (e.g. privacy)'), {
      target: { value: 'privacy' },
    });
    fireEvent.click(screen.getByText('Create page'));

    expect(await screen.findByText(/already exists/)).toBeInTheDocument();
  });

  it('clears the input and reloads after a successful create', async () => {
    const calls = stubFetch(201);
    render(<AdminLegal />);

    const input = (await screen.findByPlaceholderText('New page slug (e.g. privacy)')) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'terms' } });
    fireEvent.click(screen.getByText('Create page'));

    await waitFor(() => expect(input.value).toBe(''));
    expect(calls.filter((c) => c.method === 'GET' && c.url === '/api/admin/legal')).toHaveLength(2);
    expect(screen.queryByText(/Could not create/)).not.toBeInTheDocument();
  });

  it('keeps the create button disabled until a slug is typed', async () => {
    stubFetch(201);
    render(<AdminLegal />);
    expect(await screen.findByText('Create page')).toBeDisabled();
  });
});
