// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect } from 'react';

import { useSite } from '../theme/SiteContext';

/** Between a page's own title and the site's name — the same separator the server writes. */
export const TITLE_SEPARATOR = ' — ';

/** The document title for a page: `Page — Site`, or the site name alone when the page has no title of its own. */
export function composeTitle(page: string | null | undefined, siteName: string): string {
  const own = page?.trim();
  return own && own !== siteName ? `${own}${TITLE_SEPARATOR}${siteName}` : siteName;
}

/**
 * Keeps `document.title` about the page being shown (core#172).
 *
 * Every page used to be titled "Mosaicast" — the feed, every episode, search results, admin, the 404 — in the
 * server's HTML and after every client navigation, since nothing wrote the title at all. It is the one string
 * a tab, a bookmark, a history entry and a screen reader's page announcement all read. The server writes the
 * same form into the shell it sends (`IndexHtmlService`), so a reload and a client navigation agree.
 *
 * @param page the page's own title, or null/empty for the site's front page — and for a page still loading,
 *             which keeps the previous title rather than flashing the site name in between
 */
export function useDocumentTitle(page: string | null | undefined): void {
  const siteName = useSite().site?.name?.trim() || 'Mosaicast';
  useEffect(() => {
    document.title = composeTitle(page, siteName);
  }, [page, siteName]);
}
