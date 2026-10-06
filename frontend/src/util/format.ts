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

/**
 * An episode's place in its feed as a compact label: `S5 · E1`, or `S05 · E01` with `pad`. Either half stands
 * on its own when the other is missing — real feeds number a season and leave its prologue unnumbered (Apple's
 * spec has no `itunes:episode` 0, so Acast drops it), and that episode still belongs in season 5. Neither →
 * `null`, so callers render nothing rather than an empty separator.
 */
export function formatSeasonEpisode(
  season: number | null | undefined,
  episodeNo: number | null | undefined,
  { pad = false }: { pad?: boolean } = {},
): string | null {
  const num = (n: number) => (pad ? String(n).padStart(2, '0') : String(n));
  const parts = [season != null ? `S${num(season)}` : null, episodeNo != null ? `E${num(episodeNo)}` : null];
  const present = parts.filter((p): p is string => p != null);
  return present.length > 0 ? present.join(' · ') : null;
}

/**
 * A file's size for reading: `512 B`, `3.4 KiB`, `12.0 MiB`. Unlike the storage form's MiB-only display, a
 * download of a few kilobytes says so rather than rounding up to a megabyte.
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KiB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
