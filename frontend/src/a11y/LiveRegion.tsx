// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useState } from 'react';

/**
 * The shell's one polite live region (core#172, WCAG 4.1.3).
 *
 * There was none anywhere in the document, so nothing that changed without a page load was ever announced:
 * an episode starting, a setting saved, a list filtered down to three results. One region for the whole
 * shell rather than one per component, because a live region has to exist *before* its text changes to be
 * announced reliably — a component that mounts its own with the message already inside says nothing in
 * several screen readers.
 *
 * Messages are plain text, in the visitor's language, and short: this reads them out over whatever the
 * visitor was doing.
 */
type Listener = (message: string) => void;
const listeners = new Set<Listener>();

/** Says `message` to assistive technology, politely — after whatever is being read now. */
export function announce(message: string): void {
  const text = message.trim();
  if (text) {
    listeners.forEach((listener) => listener(text));
  }
}

export function LiveRegion() {
  const [message, setMessage] = useState('');

  useEffect(() => {
    let clear: ReturnType<typeof setTimeout> | undefined;
    const listener: Listener = (text) => {
      // The same sentence twice in a row is not a change, so it would not be read again. Clearing first
      // and setting on the next frame makes a repeat ("Saved", "Saved") a new announcement.
      setMessage('');
      requestAnimationFrame(() => setMessage(text));
      clearTimeout(clear);
      // Emptied afterwards, so a screen reader browsing the page does not find a stale sentence in it.
      clear = setTimeout(() => setMessage(''), 7000);
    };
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
      clearTimeout(clear);
    };
  }, []);

  return (
    <div className="mc-sr-only" role="status" aria-live="polite" aria-atomic="true">
      {message}
    </div>
  );
}
