// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { Modal } from './Modal';

/**
 * A caller written the way every real one is: `onClose` an inline arrow, so its identity changes on every
 * render of the page behind the dialog.
 */
function Harness() {
  const [open, setOpen] = useState(false);
  const [ticks, setTicks] = useState(0);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      {/* Something on the page that re-renders it while the dialog is up — a poll, a refresh. */}
      <button type="button" data-testid="tick" onClick={() => setTicks(ticks + 1)}>
        {ticks}
      </button>
      {open && (
        <Modal label="Dialog" onClose={() => setOpen(false)}>
          <input aria-label="Field" />
        </Modal>
      )}
    </>
  );
}

function openWithTheKeyboardFocusOnTheOpener() {
  render(<Harness />);
  const opener = screen.getByRole('button', { name: 'Open' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

describe('Modal', () => {
  it('moves focus into the sheet on open', () => {
    openWithTheKeyboardFocusOnTheOpener();
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true);
  });

  it('hands focus back to the opener when Escape closes it', () => {
    // It used to check "is focus still inside the dialog?" in a cleanup that runs after React has removed
    // the dialog, when focus has already fallen to <body> — so the answer was always no and a keyboard
    // visitor was dropped at the top of the document.
    const opener = openWithTheKeyboardFocusOnTheOpener();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(opener);
  });

  it('hands focus back when the scrim closes it', () => {
    const opener = openWithTheKeyboardFocusOnTheOpener();

    // What a browser does before the click: the scrim is not focusable, so pressing on it drops focus to
    // <body>. A restore that only trusted "focus is still inside" never fired for this dismissal.
    (document.activeElement as HTMLElement).blur();
    fireEvent.click(screen.getByRole('dialog'));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(opener);
  });

  it('leaves the caret where it is when the page behind re-renders', () => {
    // With `onClose` as an effect dependency, every render of the caller tore the effect down and set it
    // up again: focus went to the opener behind the scrim and then to the sheet — out of the field that
    // was being typed into.
    openWithTheKeyboardFocusOnTheOpener();
    const field = screen.getByLabelText('Field');
    field.focus();

    act(() => {
      fireEvent.click(screen.getByTestId('tick'));
    });

    expect(document.activeElement).toBe(field);
  });
});
