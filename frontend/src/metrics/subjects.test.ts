// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { subjectOf } from './subjects';

describe('subjectOf', () => {
  it('maps public pages to counted subjects', () => {
    expect(subjectOf('/')).toBe('home');
    expect(subjectOf('/episodes/the-cast-s01e06')).toBe('episode/the-cast-s01e06');
    expect(subjectOf('/feeds/the-cast')).toBe('feed/the-cast');
    expect(subjectOf('/p/bingo/board/3')).toBe('page/bingo');
    expect(subjectOf('/legal/imprint')).toBe('legal/imprint');
    expect(subjectOf('/about')).toBe('about');
    expect(subjectOf('/search')).toBe('search');
  });

  it('counts nothing private or unknown', () => {
    expect(subjectOf('/admin/reach')).toBeNull();
    expect(subjectOf('/account')).toBeNull();
    expect(subjectOf('/notifications')).toBeNull();
    expect(subjectOf('/cookies')).toBeNull();
    expect(subjectOf('/episodes')).toBeNull();
  });
});
