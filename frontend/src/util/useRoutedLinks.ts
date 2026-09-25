// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

/** Paths the server answers itself — following one is meant to leave the shell. */
const SERVER_PATHS = ['/api/', '/oauth2/', '/login/', '/logout', '/feed.xml', '/sitemap.xml', '/robots.txt'];

/**
 * Hands same-origin links inside injected HTML to the router.
 *
 * Markup rendered with `dangerouslySetInnerHTML` — show notes, a feed description — contains plain `<a>`
 * elements, not router links, so an internal one (`/episodes/…` in a podcaster's notes) did a full page load:
 * the shell torn down and playback with it (core#169). External links open a new tab instead
 * (`sanitizeFeedHtml`); this is the other half, for the links that belong in this tab.
 *
 * One listener on the container rather than per link: the markup is replaced wholesale whenever it changes,
 * and a delegated click survives that. Keyboard activation needs nothing extra — Enter on a focused link
 * dispatches the same `click`.
 *
 * Stands down for anything a visitor did on purpose: a modified or non-primary click (new tab, new window,
 * download), a link with its own `target` or `download`, and paths the server answers itself.
 */
export function useRoutedLinks<T extends HTMLElement>(): (element: T | null) => void {
  // State rather than a ref object: the container is often rendered only once data has loaded, and an
  // effect watching a ref object never learns that it was filled in.
  const [container, setContainer] = useState<T | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!container) {
      return;
    }
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey
        || event.altKey) {
        return;
      }
      const anchor = (event.target as Element | null)?.closest?.('a[href]');
      if (!anchor || !container.contains(anchor) || anchor.hasAttribute('target')
        || anchor.hasAttribute('download')) {
        return;
      }
      let url: URL;
      try {
        url = new URL(anchor.getAttribute('href') ?? '', window.location.href);
      } catch {
        return;
      }
      if (url.origin !== window.location.origin || SERVER_PATHS.some((p) => url.pathname.startsWith(p))) {
        return;
      }
      event.preventDefault();
      navigate(url.pathname + url.search + url.hash);
    };
    container.addEventListener('click', onClick);
    return () => container.removeEventListener('click', onClick);
  }, [container, navigate]);

  return useCallback((element: T | null) => setContainer(element), []);
}
