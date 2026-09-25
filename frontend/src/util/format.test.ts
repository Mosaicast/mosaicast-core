// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { formatDate, formatDuration, formatPublishedDate } from './format';

describe('formatDuration', () => {
  it('formats h:mm:ss and m:ss, and handles empty', () => {
    expect(formatDuration(4122)).toBe('1:08:42');
    expect(formatDuration(522)).toBe('8:42');
    expect(formatDuration(5)).toBe('0:05');
    expect(formatDuration(null)).toBe('');
    expect(formatDuration(-1)).toBe('');
  });
});

describe('formatDate', () => {
  it('formats an ISO instant and tolerates empty/invalid', () => {
    expect(formatDate('2026-06-21T00:00:00Z', 'en-US')).toMatch(/2026/);
    expect(formatDate(null)).toBe('');
    expect(formatDate('not-a-date')).toBe('');
  });

  it('shows a publication as the calendar date it was published on, wherever the reader is (#199)', () => {
    // 23:30 UTC on the 5th is the 6th in Berlin; the show published it on the 5th.
    expect(formatPublishedDate('2026-07-05T23:30:00Z', 'en-GB')).toBe('5 Jul 2026');
    expect(formatPublishedDate('2026-07-05T00:30:00Z', 'en-GB')).toBe('5 Jul 2026');
  });
});
