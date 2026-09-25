// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect } from 'react';

import { announce } from './LiveRegion';

/**
 * A "saved" confirmation that is also heard (core#172, WCAG 4.1.3).
 *
 * The confirmations were a muted span that appeared after a save — visible, and silent to a screen reader,
 * since text that mounts already filled in is not announced. This shows the same span and says it through
 * the shell's live region.
 */
export function SavedNote({ show, children, as: Tag = 'span' }: { show: boolean; children: string; as?: 'span' | 'p' }) {
  useEffect(() => {
    if (show) {
      announce(children);
    }
  }, [show, children]);
  return show ? <Tag className="mc-muted">{children}</Tag> : null;
}
