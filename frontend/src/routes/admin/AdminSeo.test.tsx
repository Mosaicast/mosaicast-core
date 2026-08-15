// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import type { SeoView } from '../../api/types';
import { AdminSeo } from './AdminSeo';

const seo: SeoView = {
  policy: 'custom',
  blocked: ['GPTBot'],
  known: [
    { agent: 'GPTBot', operator: 'OpenAI', purpose: 'training' },
    { agent: 'ClaudeBot', operator: 'Anthropic', purpose: 'training' },
  ],
};

const put = vi.fn(async (_path: string, body: unknown) => ({ ...seo, ...(body as object) }) as SeoView);

vi.mock('../../api/client', () => ({
  api: {
    get: vi.fn(async () => seo),
    put: (path: string, body: unknown) => put(path, body),
  },
}));

describe('AdminSeo', () => {
  beforeEach(() => put.mockClear());

  it('prefills the saved policy and block list', async () => {
    render(<AdminSeo />);

    const custom = await screen.findByRole('radio', { name: /Choose individually/ });
    expect((custom as HTMLInputElement).checked).toBe(true);
    // The per-agent list only exists under `custom`, and reflects what was saved.
    expect((await screen.findByRole<HTMLInputElement>('checkbox', { name: /GPTBot/ })).checked).toBe(true);
    expect((await screen.findByRole<HTMLInputElement>('checkbox', { name: /ClaudeBot/ })).checked).toBe(
      false,
    );
  });

  it('hides the per-agent list unless the policy is custom', async () => {
    render(<AdminSeo />);
    await screen.findByRole('radio', { name: /Allow/ });

    fireEvent.click(screen.getByRole('radio', { name: /Block all/ }));

    // "Block all" means core's whole catalog, so there is nothing to tick.
    expect(screen.queryByRole('checkbox', { name: /GPTBot/ })).toBeNull();
  });

  it('says plainly that robots.txt is not enforcement', async () => {
    render(<AdminSeo />);

    // A setting that reads like a lock and is not one is worse than no setting — this sentence is the
    // reason the panel is honest, so it is pinned rather than left to survive a copy edit.
    expect(await screen.findByText(/request, not a barrier/)).toBeInTheDocument();
  });

  it('sends the policy and the block list on save', async () => {
    render(<AdminSeo />);
    await screen.findByRole('checkbox', { name: /ClaudeBot/ });

    fireEvent.click(screen.getByRole('checkbox', { name: /ClaudeBot/ }));
    fireEvent.click(screen.getByRole('button', { name: /Save/ }));

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(put).toHaveBeenCalledWith('/api/admin/seo', {
      policy: 'custom',
      blocked: ['GPTBot', 'ClaudeBot'],
    });
  });
});
