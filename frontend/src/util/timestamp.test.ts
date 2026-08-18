// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { formatTimestampParam, parseTimestamp, withTimestamp } from './timestamp';

/**
 * The accepted-forms table. `web/TimestampParamTest.java` asserts the same rows against the server-side
 * parser — the two grammars have to agree or a link renders one preview and seeks to a different place.
 */
const ACCEPTED: Array<[string, number]> = [
  ['754', 754],
  ['0', 0],
  ['754s', 754],
  ['12:34', 754],
  ['1:02:03', 3723],
  ['0:05', 5],
  ['90:00', 5400],
  ['1h02m03s', 3723],
  ['1h2m', 3720],
  ['90m', 5400],
  ['2h', 7200],
  ['  12:34  ', 754],
  ['1H02M03S', 3723],
];

const REJECTED = [
  '',
  '   ',
  'abc',
  '-5',
  '12:60',
  '1:2:3',
  '12:34:56:78',
  'h',
  'm',
  '86401',
  '25h',
  '1e3',
  '<script>alert(1)</script>',
  '754; DROP TABLE blob',
];

describe('parseTimestamp', () => {
  it.each(ACCEPTED)('reads %s as %i seconds', (raw, expected) => {
    expect(parseTimestamp(raw)).toBe(expected);
  });

  it.each(REJECTED)('ignores %s', (raw) => {
    expect(parseTimestamp(raw)).toBeNull();
  });

  it('treats an absent parameter as no timestamp', () => {
    expect(parseTimestamp(null)).toBeNull();
    expect(parseTimestamp(undefined)).toBeNull();
  });
});

describe('formatTimestampParam', () => {
  it('always emits bare whole seconds', () => {
    expect(formatTimestampParam(754)).toBe('754');
    expect(formatTimestampParam(754.9)).toBe('754');
    expect(formatTimestampParam(-3)).toBe('0');
  });
});

describe('withTimestamp', () => {
  it('adds, replaces and removes t on a relative URL', () => {
    expect(withTimestamp('/episodes/kraken', 754)).toBe('/episodes/kraken?t=754');
    expect(withTimestamp('/episodes/kraken?t=10', 754)).toBe('/episodes/kraken?t=754');
    expect(withTimestamp('/episodes/kraken?t=10', null)).toBe('/episodes/kraken');
  });

  it('leaves the filter axes alone (§6.1)', () => {
    expect(withTimestamp('/feeds/main?season=2&tag=x', 30)).toBe('/feeds/main?season=2&tag=x&t=30');
    expect(withTimestamp('/feeds/main?season=2&t=30', null)).toBe('/feeds/main?season=2');
  });

  it('round-trips an absolute URL', () => {
    expect(withTimestamp('https://podcast.test/episodes/kraken', 754)).toBe(
      'https://podcast.test/episodes/kraken?t=754',
    );
  });
});
