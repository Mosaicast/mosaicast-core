// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * A dialog over the page: scrim, sheet, Escape, focus.
 *
 * **Rendered into `document.body`, not where it is written.** `position: fixed` resolves against the nearest
 * ancestor with a transform or filter rather than the viewport, and `z-index` only competes inside its own
 * stacking context — so a dialog opened from inside the episode hero landed offset and painted *under* the
 * sidebar beside it. A portal is the only fix that does not depend on every future ancestor staying free of
 * both, which is not a property anyone can maintain from here.
 *
 * `aria-modal` is a claim about behaviour, so the behaviour has to be there — focus moves into the sheet on
 * open and returns to whatever opened it on close, otherwise a keyboard visitor is dropped at the top of the
 * document with no way back to the button they pressed.
 *
 * Deliberately unstyled beyond the two structural classes: a caller renders its own content and owns the
 * padding, so the primitive stays about behaviour rather than about looks.
 */
export function Modal({
  label,
  onClose,
  children,
}: {
  /** The accessible name of the dialog — what a screen reader announces on open. */
  label: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const sheet = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    sheet.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      // Only if focus is still inside the dialog: a close that happened *because* the visitor clicked
      // somewhere else should not yank them back.
      if (opener && sheet.current?.contains(document.activeElement)) {
        opener.focus();
      }
    };
  }, [onClose]);

  return createPortal(
    <div
      className="mc-dialog__overlay"
      role="dialog"
      aria-modal="true"
      aria-label={label}
      // Only a press that both starts and ends on the scrim closes — dragging a selection out of the sheet
      // and releasing over the backdrop is not a dismissal.
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="mc-dialog__sheet" ref={sheet} tabIndex={-1}>
        {children}
      </div>
    </div>,
    document.body,
  );
}
