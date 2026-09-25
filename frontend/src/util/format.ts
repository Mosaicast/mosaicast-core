// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/** Presentation formatters. Runtime/date come from the feed snapshot (§4.2); we only format them. */

/** `4122` → `"1:08:42"`, `522` → `"8:42"`. Null/negative → `""`. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || seconds < 0) {
    return '';
  }
  const s = Math.floor(seconds % 60);
  const m = Math.floor((seconds / 60) % 60);
  const h = Math.floor(seconds / 3600);
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

/** An ISO-8601 instant → a locale-aware short date (e.g. `Jun 21, 2026`). Null/invalid → `""`. */
export function formatDate(iso: string | null | undefined, locale?: string): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: 'numeric' }).format(date);
}

/**
 * An episode's publication date, as the calendar date the show published it — not as the local day that
 * instant falls on (#199).
 *
 * `formatDate` renders an instant in the browser's timezone, so an episode published at 23:30 UTC showed a
 * day later in Berlin and a day earlier in Los Angeles, while the sitemap, the OG tags and the server's own
 * copy of the page printed the UTC date. For a podcast, "published on the 5th" is a fact about the show;
 * UTC is the one reading every surface agrees on. Timestamps that really are about a moment — a token
 * created, a notification received — stay local.
 */
export function formatPublishedDate(iso: string | null | undefined, locale?: string): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' })
    .format(date);
}
