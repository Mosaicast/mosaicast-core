// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * Converting between an instant and a `datetime-local` input, which has no zone and is read in the admin's
 * own. The API speaks instants; the form speaks the admin's wall clock.
 */

/** An instant as a `datetime-local` value in the admin's time zone. */
export function toLocalInput(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** A `datetime-local` value, read in the admin's time zone, as an ISO instant; null when empty. */
export function fromLocalInput(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}

/** The admin's time zone by name, so a form can say which wall clock its times are in. */
export function localZoneName(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
