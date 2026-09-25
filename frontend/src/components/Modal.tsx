// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useLayoutEffect, useRef, type ReactNode } from 'react';
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

  // The latest `onClose`, read at the moment of closing. Every caller passes an inline arrow, so as an
  // effect dependency it re-ran the effect on each render of the page behind: focus went to the opener and
  // back to the sheet, out of whatever field was being typed into.
  const close = useRef(onClose);
  useLayoutEffect(() => {
    close.current = onClose;
  });

  // A layout effect, so the cleanup runs while the sheet is still in the document. A passive one runs after
  // React has removed it, when focus has already fallen to <body> — too late to tell "focus was in here"
  // from "the visitor clicked somewhere else", and the restore never happened.
  useLayoutEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    sheet.current?.focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close.current();
      }
    };
    document.addEventListener('keydown', onKey);

    const dialog = sheet.current;
    return () => {
      document.removeEventListener('keydown', onKey);
      // Only if nothing else has taken focus: a close that happened *because* the visitor focused something
      // else should not yank them back. <body> counts as nothing — pressing on the scrim, which is not
      // focusable, drops focus there before the click that dismisses the dialog.
      const active = document.activeElement;
      if (opener?.isConnected && (active === document.body || dialog?.contains(active))) {
        opener.focus();
      }
    };
  }, []);

  return createPortal(
    // The scrim is a pointer affordance; Escape is the keyboard one, registered on `document` in the
    // effect above, so there is nothing a pointer can do here that a keyboard cannot. A key handler on
    // the scrim would be the wrong place for it — the scrim is not focusable and must not become so, or
    // Tab would land on the backdrop.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions
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
