// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * The `?t=` deep-link grammar (ARCHITECTURE §6.4) — the one place the shell agrees with the server on what
 * a shared timestamp looks like. `web/TimestampParam.java` implements the same grammar for the meta tags it
 * injects, and the two are tested against the same table of cases.
 *
 * Three notations are accepted because all three are what people actually paste: the bare seconds a machine
 * writes (`754`), the clock a player shows (`12:34`, `1:02:03`), and the unit form other podcast apps emit
 * (`1h02m03s`, `90m`). Anything else — and anything out of range — is **ignored rather than an error**: a
 * mangled timestamp in a link someone forwarded should still open the episode.
 */

/** The largest position a link may carry: 24 h. Longer is a typo or a probe, not an episode. */
const MAX_SECONDS = 86_400;

const PLAIN = /^\d+$/;
const MMSS = /^(\d{1,3}):([0-5]\d)$/;
const HHMMSS = /^(\d{1,2}):([0-5]\d):([0-5]\d)$/;
// Digit runs are bounded so no group can overflow the arithmetic below; the range check rejects the
// large-but-parsable ones.
const UNITS = /^(?:(\d{1,6})h)?(?:(\d{1,6})m)?(?:(\d{1,6})s)?$/;

/**
 * Reads a `t` parameter as a position in seconds.
 *
 * @param raw the parameter value as it arrived, or null/undefined when absent
 * @returns whole seconds, or `null` when the value is absent, unparsable, or out of range
 */
export function parseTimestamp(raw: string | null | undefined): number | null {
  if (raw == null) {
    return null;
  }
  const value = raw.trim().toLowerCase();
  if (!value) {
    return null;
  }

  let seconds: number | null = null;
  if (PLAIN.test(value)) {
    seconds = Number(value);
  } else {
    const clock = HHMMSS.exec(value) ?? MMSS.exec(value);
    if (clock) {
      const parts = clock.slice(1).map(Number);
      seconds = parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : parts[0] * 60 + parts[1];
    } else {
      const units = UNITS.exec(value);
      // The unit pattern is all-optional, so it also matches the empty string — which `value` cannot be,
      // but `"h"` or `"m"` would slip through as zero without this check.
      if (units && (units[1] || units[2] || units[3])) {
        seconds = Number(units[1] ?? 0) * 3600 + Number(units[2] ?? 0) * 60 + Number(units[3] ?? 0);
      }
    }
  }

  if (seconds == null || !Number.isFinite(seconds) || seconds < 0 || seconds > MAX_SECONDS) {
    return null;
  }
  return Math.floor(seconds);
}

/**
 * The canonical wire form of a position — always bare seconds, so two links to the same moment are the same
 * URL however the sharer typed the time.
 *
 * @param seconds a position in seconds
 * @returns the value for `?t=`
 */
export function formatTimestampParam(seconds: number): string {
  return String(Math.max(0, Math.floor(seconds)));
}

/**
 * Adds, replaces or removes a `t` parameter on a URL, leaving every other parameter (the filter axes of
 * §6.1) untouched.
 *
 * @param url      an absolute or root-relative URL
 * @param seconds  the position to link to, or null to drop the parameter
 * @returns the URL with `t` set or removed
 */
export function withTimestamp(url: string, seconds: number | null): string {
  // A base is only needed to parse a root-relative path; it never appears in the output for one.
  const relative = !/^https?:\/\//i.test(url);
  const parsed = new URL(url, relative ? 'http://mosaicast.invalid' : undefined);
  if (seconds == null) {
    parsed.searchParams.delete('t');
  } else {
    parsed.searchParams.set('t', formatTimestampParam(seconds));
  }
  return relative ? parsed.pathname + parsed.search + parsed.hash : parsed.toString();
}
