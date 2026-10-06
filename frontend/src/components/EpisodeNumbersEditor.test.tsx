// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { EpisodeNumbersEditor } from './EpisodeNumbersEditor';

const get = vi.fn();
const put = vi.fn();
const del = vi.fn();

vi.mock('../api/client', () => ({
  api: {
    get: (path: string) => get(path),
    put: (path: string, body: unknown) => put(path, body),
    del: (path: string) => del(path),
  },
}));

let role: string | null = 'podcaster';
vi.mock('../auth/UserContext', () => ({
  useUser: () => ({ user: role ? { id: 'u1', role } : null }),
}));

// The case core#264 is about: the feed says season 5 and cannot say "episode 0".
const fromFeed = { season: 5, episodeNo: null, pinned: false, feedSeason: 5, feedEpisodeNo: null };
const byHand = { ...fromFeed, episodeNo: 0, pinned: true };

describe('EpisodeNumbersEditor', () => {
  beforeEach(() => {
    role = 'podcaster';
    get.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('lets a podcaster call a prologue episode 0, and tells the page to re-read', async () => {
    get.mockResolvedValue(fromFeed);
    put.mockResolvedValueOnce(byHand);
    const onChange = vi.fn();

    render(<EpisodeNumbersEditor slug="s05-prolog" onChange={onChange} />);

    expect(await screen.findByText('The feed says S5.')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Episode' }), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save numbers' }));

    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/api/admin/episodes/s05-prolog/numbers', { season: 5, episodeNo: 0 }),
    );
    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(await screen.findByText(/Set by hand\./)).toBeInTheDocument();
  });

  it('hands pinned numbers back to the feed', async () => {
    get.mockResolvedValue(byHand);
    del.mockResolvedValueOnce(fromFeed);

    render(<EpisodeNumbersEditor slug="s05-prolog" onChange={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: "Use the feed's numbers" }));

    await waitFor(() => expect(del).toHaveBeenCalledWith('/api/admin/episodes/s05-prolog/numbers'));
    await waitFor(() => expect(screen.queryByRole('button', { name: "Use the feed's numbers" })).toBeNull());
    expect(screen.getByRole('spinbutton', { name: 'Episode' })).toHaveValue(null);
  });

  it('renders nothing for a listener, and asks the server nothing', () => {
    role = 'fan';
    const { container } = render(<EpisodeNumbersEditor slug="s05-prolog" onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
    expect(get).not.toHaveBeenCalled();
  });
});
