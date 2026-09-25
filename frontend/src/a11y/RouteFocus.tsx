// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

/** How long to wait for a page's heading to render before settling for the main landmark. */
const HEADING_WAIT_MS = 1500;

/**
 * Moves focus to the new page's heading after a client-side navigation (core#172, WCAG 2.4.3).
 *
 * A full page load puts a screen reader at the top of the new document; a client-side route change did
 * nothing at all. Focus stayed on the link that was followed — or on `<body>` when that link was part of the
 * page that just went away — so nothing announced that the page had changed, and the next Tab continued
 * from somewhere in the old one.
 *
 * On a change of *path* only: filters and sort order live in the query string, and a visitor narrowing a
 * list must not be thrown back to its heading on every change. A page that placed focus itself (the search
 * page's field) is left alone. The heading of a page still loading its data renders a moment later, so it is
 * waited for; failing that, the main landmark takes the focus instead.
 */
export function RouteFocus() {
  const { pathname } = useLocation();
  const initial = useRef(true);

  useEffect(() => {
    if (initial.current) {
      // A first load is already at the top of a new document; moving focus there would only fight the browser.
      initial.current = false;
      return;
    }
    const main = document.getElementById('main');
    if (!main) {
      return;
    }
    let done = false;
    const place = (target: HTMLElement) => {
      done = true;
      observer.disconnect();
      clearTimeout(timer);
      // A page that put the focus somewhere itself — the search field — has decided; do not overrule it.
      if (main.contains(document.activeElement) && document.activeElement !== main) {
        return;
      }
      if (!target.hasAttribute('tabindex')) {
        target.setAttribute('tabindex', '-1');
      }
      target.focus();
    };
    const tryHeading = () => {
      const heading = main.querySelector<HTMLElement>('h1');
      if (heading && !done) {
        place(heading);
      }
    };
    const observer = new MutationObserver(tryHeading);
    const timer = setTimeout(() => !done && place(main), HEADING_WAIT_MS);
    observer.observe(main, { childList: true, subtree: true });
    // After this commit has painted, so the heading of a page that renders at once is already there.
    const frame = requestAnimationFrame(tryHeading);
    return () => {
      done = true;
      observer.disconnect();
      clearTimeout(timer);
      cancelAnimationFrame(frame);
    };
  }, [pathname]);

  return null;
}
