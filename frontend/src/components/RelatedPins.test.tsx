// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { RelatedPins } from './RelatedPins';

const get = vi.fn();
const post = vi.fn();
const del = vi.fn();

vi.mock('../api/client', () => ({
  api: {
    get: (path: string) => get(path),
    post: (path: string, body: unknown) => post(path, body),
    del: (path: string) => del(path),
  },
}));

let role: string | null = 'podcaster';
vi.mock('../auth/UserContext', () => ({
  useUser: () => ({ user: role ? { id: 'u1', role } : null }),
}));

const pinned = [{ id: 'e1', slug: 'deep-water', title: 'Deep Water' }];

describe('RelatedPins', () => {
  beforeEach(() => {
    role = 'podcaster';
    get.mockReset();
    post.mockReset();
    del.mockReset();
    get.mockResolvedValue(pinned);
  });

  it('shows a podcaster what is pinned, and lets them unpin it', async () => {
    del.mockResolvedValueOnce([]);
    const onChange = vi.fn();

    render(<RelatedPins slug="the-lighthouse" onChange={onChange} />);

    expect(await screen.findByText('Deep Water')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Unpin Deep Water/ }));

    await waitFor(() => expect(del).toHaveBeenCalledWith('/api/admin/episodes/the-lighthouse/pins/deep-water'));
    // The public list is computed from the pins, so the page has to re-read it rather than assume.
    await waitFor(() => expect(onChange).toHaveBeenCalled());
  });

  it('pins an episode found by search', async () => {
    get.mockImplementation((path: string) =>
      path.startsWith('/api/episodes/search')
        ? Promise.resolve({ items: [{ id: 'e2', slug: 'the-harbour', title: 'The Harbour' }] })
        : Promise.resolve(pinned),
    );
    post.mockResolvedValueOnce(pinned);

    render(<RelatedPins slug="the-lighthouse" onChange={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Pin an episode' }));
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'harbour' } });

    const result = await screen.findByRole('button', { name: 'The Harbour' }, { timeout: 3000 });
    fireEvent.click(result);

    await waitFor(() =>
      expect(post).toHaveBeenCalledWith('/api/admin/episodes/the-lighthouse/pins', {
        relatedSlug: 'the-harbour',
      }),
    );
  });

  it('is invisible to a fan, and asks the server for nothing', async () => {
    role = 'fan';

    const { container } = render(<RelatedPins slug="the-lighthouse" onChange={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
    // The server enforces this too; not asking keeps a 403 out of every fan's console.
    await waitFor(() => expect(get).not.toHaveBeenCalled());
  });

  it('is invisible to an anonymous visitor', () => {
    role = null;

    const { container } = render(<RelatedPins slug="the-lighthouse" onChange={vi.fn()} />);

    expect(container).toBeEmptyDOMElement();
  });
});
