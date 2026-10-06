// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../i18n';
import { ApiError } from '../api/client';
import { DataExport } from './DataExport';

const get = vi.fn();
const post = vi.fn();

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>();
  return {
    ...actual,
    api: { get: (path: string) => get(path), post: (path: string) => post(path) },
  };
});

const running = {
  id: 'x1',
  status: 'running',
  requestedAt: '2026-10-06T10:00:00Z',
  finishedAt: null,
  expiresAt: null,
  sizeBytes: null,
  nextAllowedAt: '2999-01-01T00:00:00Z',
};
const ready = {
  ...running,
  status: 'ready',
  finishedAt: '2026-10-06T10:01:00Z',
  expiresAt: '2026-10-13T10:01:00Z',
  sizeBytes: 3482,
};

describe('DataExport', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('starts an export for someone who never asked, then says it is being prepared', async () => {
    get.mockResolvedValue(undefined);
    post.mockResolvedValueOnce(running);

    render(<DataExport />);
    fireEvent.click(await screen.findByRole('button', { name: 'Prepare my data' }));

    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/me/export'));
    expect(await screen.findByText('Preparing your data…')).toBeInTheDocument();
    // No second request while one is running.
    expect(screen.queryByRole('button', { name: 'Prepare my data' })).toBeNull();
  });

  it('offers the download as a plain link, with its expiry and size, and says when the next one is possible', async () => {
    get.mockResolvedValue(ready);

    render(<DataExport />);

    const link = await screen.findByRole('link', { name: 'Download' });
    expect(link).toHaveAttribute('href', '/api/me/export/x1/download');
    expect(screen.getByText(/Available until .* · 3\.4 KiB/)).toBeInTheDocument();
    expect(screen.getByText(/You can prepare a new one from/)).toBeInTheDocument();
  });

  it('lets someone try again straight after a failure, and says so when it is too soon', async () => {
    get.mockResolvedValue({ ...running, status: 'failed', nextAllowedAt: '2026-10-06T10:00:30Z' });
    post.mockRejectedValueOnce(new ApiError(429, 'Too Many Requests'));

    render(<DataExport />);
    expect(await screen.findByText("Preparing your data didn't work. You can try again.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Prepare my data' }));

    expect(await screen.findByText("You've already prepared your data recently. Try again later.")).toBeInTheDocument();
  });
});
