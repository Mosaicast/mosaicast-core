// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import {
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import { useLocation } from 'react-router-dom';

/** What counts as a menu item for keyboard navigation — headings and separators are deliberately absent. */
const ITEM_SELECTOR = '[role="menuitem"]';

/**
 * A small controlled dropdown menu. Replaces native `<details>` (which never closes on an outside click or
 * on selecting an item): opens on the trigger, and closes on an outside click, `Escape`, selecting an item
 * inside the panel (anything with `role="menuitem"`), or a route change. A click on some other control in
 * the panel — a button that acts in place, like the inbox's per-row "mark as read" — leaves it open. The markup reuses the `mc-menu` styles.
 *
 * Keyboard: the arrow keys move between items, `Home`/`End` jump to the ends, and closing returns focus to
 * the trigger. Opening with `ArrowDown` lands on the first item, which is how someone driving the menu from
 * the keyboard expects to enter it.
 */
export function Dropdown({
  trigger,
  triggerClassName,
  className,
  ariaLabel,
  onOpen,
  align = 'end',
  children,
}: {
  /** Content of the trigger button (label, avatar, icon). */
  trigger: ReactNode;
  /** Class for the trigger button (e.g. `mc-btn mc-btn--ghost`). */
  triggerClassName?: string;
  /** Extra class on the menu wrapper, for a caller that needs to address the control from CSS. */
  className?: string;
  /** Accessible label for the trigger when its content is not text (e.g. an icon). */
  ariaLabel?: string;
  /**
   * Called the first time the menu opens and on every open after.
   *
   * For a menu whose contents cost a request: most page loads never open one, so fetching on mount would
   * buy latency nobody spends. The callback is responsible for not re-fetching what it already has.
   */
  onOpen?: () => void;
  /**
   * Which edge the panel hangs from. `end` (the default) is right-aligned, which is right for every trigger
   * in the header's action cluster; `start` is for a trigger at the left edge, where a right-aligned panel
   * would open off the side of the viewport.
   */
  align?: 'start' | 'end';
  /** The menu items. A click anywhere inside closes the menu (after the item's own handler runs). */
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  // Set when the menu is opened by keyboard, so the effect below knows to move focus into it.
  const focusFirstOnOpen = useRef(false);
  const location = useLocation();

  const items = useCallback(
    () => Array.from(panelRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR) ?? []),
    [],
  );

  /**
   * Closes, and puts focus back on the trigger.
   *
   * The panel is unmounted on close, so without this focus is left on a node that no longer exists and the
   * next Tab starts from the top of the document.
   */
  const close = useCallback((restoreFocus = true) => {
    setOpen(false);
    if (restoreFocus) {
      triggerRef.current?.focus();
    }
  }, []);

  // Close on an outside click or Escape while open.
  useEffect(() => {
    if (!open) {
      return;
    }
    const onPointerDown = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        // A click elsewhere is already a statement about where attention went; stealing focus back to the
        // trigger would undo it.
        close(false);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        close();
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, close]);

  // Entering the menu from the keyboard should land on something.
  useEffect(() => {
    if (open && focusFirstOnOpen.current) {
      focusFirstOnOpen.current = false;
      items()[0]?.focus();
    }
  }, [open, items]);

  // Close when navigating away (e.g. after selecting Account / Admin).
  useEffect(() => {
    setOpen(false);
  }, [location]);

  /**
   * Closes when an <em>item</em> is selected — not on any click that lands in the panel.
   *
   * This used to close on every click anywhere inside, which is fine for a menu of links and wrong for a
   * panel with controls in it: the notification inbox has a "mark as read" tick per row, and pressing one
   * shut the panel the reader was working through. A menu closes because you picked something and are done
   * with it, so the test is whether the click was on something pickable.
   */
  const onPanelClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest(ITEM_SELECTOR)) {
      close(false);
    }
  };

  /** Arrow / Home / End roving focus across the items, skipping headings and separators. */
  const onPanelKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const keys = ['ArrowDown', 'ArrowUp', 'Home', 'End'];
    if (!keys.includes(event.key)) {
      return;
    }
    const all = items();
    if (all.length === 0) {
      return;
    }
    event.preventDefault();
    const current = all.indexOf(document.activeElement as HTMLElement);
    let next: number;
    if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = all.length - 1;
    } else if (event.key === 'ArrowDown') {
      next = current < 0 ? 0 : (current + 1) % all.length;
    } else {
      next = current <= 0 ? all.length - 1 : current - 1;
    }
    all[next]?.focus();
  };

  const onTriggerKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowDown' && !open) {
      event.preventDefault();
      focusFirstOnOpen.current = true;
      onOpen?.();
      setOpen(true);
    }
  };

  return (
    <div className={`mc-menu${className ? ` ${className}` : ''}`} ref={ref}>
      <button
        ref={triggerRef}
        type="button"
        className={`${triggerClassName ?? ''} mc-menu__summary`.trim()}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => {
          if (!open) {
            onOpen?.();
          }
          setOpen((v) => !v);
        }}
        onKeyDown={onTriggerKeyDown}
      >
        {trigger}
      </button>
      {open && (
        <div
          ref={panelRef}
          className={`mc-menu__panel${align === 'start' ? ' mc-menu__panel--start' : ''}`}
          role="menu"
          onClick={onPanelClick}
          onKeyDown={onPanelKeyDown}
        >
          {children}
        </div>
      )}
    </div>
  );
}
