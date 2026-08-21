// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { api } from '../../api/client';
import type { AdminNavItem } from '../../plugins/types';
import { AdminNavigation } from './AdminNavigation';

/**
 * Reordering is the part worth testing: the position IS the order, so an off-by-one in the splice would
 * silently save a different menu than the one on screen.
 */

function item(pluginId: string, label: string): AdminNavItem {
  return {
    pluginId,
    path: '',
    href: `/p/${pluginId}`,
    label,
    icon: null,
    pluginName: label,
    visibleTo: null,
    enabled: true,
    order: 0,
  };
}

const rows = () => screen.getAllByRole('rowheader').map((cell) => cell.textContent?.split('/p/')[0]);

afterEach(() => vi.restoreAllMocks());

describe('AdminNavigation', () => {
  it('moves a row down and saves the new positions as the order', async () => {
    const entries = [item('a', 'Alpha'), item('b', 'Beta'), item('c', 'Gamma')];
    vi.spyOn(api, 'get').mockResolvedValue(entries);
    const put = vi.spyOn(api, 'put').mockResolvedValue(entries);

    render(<AdminNavigation />);
    await waitFor(() => expect(rows()).toEqual(['Alpha', 'Beta', 'Gamma']));

    fireEvent.click(screen.getByRole('button', { name: 'Move Alpha down' }));
    expect(rows()).toEqual(['Beta', 'Alpha', 'Gamma']);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    // The payload is the position, not a number anyone typed — there is no second place for the order.
    expect(put.mock.calls[0][1]).toEqual([
      { pluginId: 'b', path: '', enabled: true, order: 0 },
      { pluginId: 'a', path: '', enabled: true, order: 1 },
      { pluginId: 'c', path: '', enabled: true, order: 2 },
    ]);
  });

  it('moves a row up, and the ends cannot go further', async () => {
    vi.spyOn(api, 'get').mockResolvedValue([item('a', 'Alpha'), item('b', 'Beta')]);

    render(<AdminNavigation />);
    await waitFor(() => expect(rows()).toEqual(['Alpha', 'Beta']));

    // Nowhere to go: the buttons stay for a stable layout but are disabled.
    expect(screen.getByRole('button', { name: 'Move Alpha up' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Move Beta down' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Move Beta up' }));
    expect(rows()).toEqual(['Beta', 'Alpha']);
  });

  it('announces a move, because the rows moving is silent', async () => {
    vi.spyOn(api, 'get').mockResolvedValue([item('a', 'Alpha'), item('b', 'Beta')]);

    render(<AdminNavigation />);
    await waitFor(() => expect(rows()).toEqual(['Alpha', 'Beta']));

    fireEvent.click(screen.getByRole('button', { name: 'Move Alpha down' }));

    expect(screen.getByText('Alpha moved to position 2 of 2')).toBeInTheDocument();
  });

  it('reorders on drop, for the pointer path', async () => {
    vi.spyOn(api, 'get').mockResolvedValue([item('a', 'Alpha'), item('b', 'Beta'), item('c', 'Gamma')]);

    const { container } = render(<AdminNavigation />);
    await waitFor(() => expect(rows()).toEqual(['Alpha', 'Beta', 'Gamma']));

    const tr = container.querySelectorAll('tbody tr');
    fireEvent.dragStart(tr[2]);
    fireEvent.dragOver(tr[0]);
    fireEvent.drop(tr[0]);

    expect(rows()).toEqual(['Gamma', 'Alpha', 'Beta']);
  });
});
