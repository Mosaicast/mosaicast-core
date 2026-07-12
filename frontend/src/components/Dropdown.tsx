// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { type ReactNode, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * A small controlled dropdown menu. Replaces native `<details>` (which never closes on an outside click or
 * on selecting an item): opens on the trigger, and closes on an outside click, `Escape`, selecting an item
 * inside the panel, or a route change. The markup reuses the `mc-menu` styles.
 */
export function Dropdown({
  trigger,
  triggerClassName,
  ariaLabel,
  children,
}: {
  /** Content of the trigger button (label, avatar, icon). */
  trigger: ReactNode;
  /** Class for the trigger button (e.g. `mc-btn mc-btn--ghost`). */
  triggerClassName?: string;
  /** Accessible label for the trigger when its content is not text (e.g. an icon). */
  ariaLabel?: string;
  /** The menu items. A click anywhere inside closes the menu (after the item's own handler runs). */
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const location = useLocation();

  // Close on an outside click or Escape while open.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // Close when navigating away (e.g. after selecting Account / Admin).
  useEffect(() => {
    setOpen(false);
  }, [location]);

  return (
    <div className="mc-menu" ref={ref}>
      <button
        type="button"
        className={`${triggerClassName ?? ''} mc-menu__summary`.trim()}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((v) => !v)}
      >
        {trigger}
      </button>
      {open && (
        <div className="mc-menu__panel" role="menu" onClick={() => setOpen(false)}>
          {children}
        </div>
      )}
    </div>
  );
}
