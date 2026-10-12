// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { fromLocalInput, toLocalInput } from './localDateTime';

describe('localDateTime', () => {
  it('round-trips an instant through the input format to the minute', () => {
    const iso = '2026-10-18T10:00:00.000Z';
    expect(fromLocalInput(toLocalInput(iso))).toBe(iso);
  });

  it('reads an empty input as no time', () => {
    expect(fromLocalInput('')).toBeNull();
  });
});
