// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { Dropdown } from './Dropdown';

function renderMenu() {
  return render(
    <MemoryRouter>
      <div>
        <span>outside</span>
        <Dropdown trigger="Menu" triggerClassName="mc-btn">
          {/* A real menu item, as every caller writes one — selecting it is what closes the menu. */}
          <button type="button" role="menuitem">
            Item
          </button>
          {/* A control that acts in place, like the inbox's per-row "mark as read". */}
          <button type="button">Tick</button>
        </Dropdown>
      </div>
    </MemoryRouter>,
  );
}

const open = () => fireEvent.click(screen.getByRole('button', { name: 'Menu' }));

describe('Dropdown', () => {
  it('is closed initially and opens on the trigger', () => {
    renderMenu();
    expect(screen.queryByText('Item')).not.toBeInTheDocument();
    open();
    expect(screen.getByText('Item')).toBeInTheDocument();
  });

  it('closes when an item is selected', () => {
    renderMenu();
    open();
    fireEvent.click(screen.getByText('Item'));
    expect(screen.queryByText('Item')).not.toBeInTheDocument();
  });

  it('stays open when a control that is not a menu item is used', () => {
    // The inbox panel has a "mark as read" tick on every row. Closing on it shut the panel the reader was
    // working through, which is why the close test is about *selecting an item* rather than about clicking.
    renderMenu();
    open();
    fireEvent.click(screen.getByText('Tick'));
    expect(screen.getByText('Tick')).toBeInTheDocument();
  });

  it('closes on an outside click', () => {
    renderMenu();
    open();
    fireEvent.pointerDown(screen.getByText('outside'));
    expect(screen.queryByText('Item')).not.toBeInTheDocument();
  });

  it('closes on Escape', () => {
    renderMenu();
    open();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('Item')).not.toBeInTheDocument();
  });
});
