// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { beforeEach, describe, expect, it } from 'vitest';

import type { ConsentPayload } from '../api/types';
import { purgeUndeclared } from './purge';

const PAYLOAD: ConsentPayload = {
  fingerprint: 'abc123',
  categories: [
    {
      id: 'analytics',
      known: true,
      services: [
        {
          name: 'Plausible Analytics',
          provider: 'Plausible Insights OÜ',
          privacyUrl: null,
          thirdCountryTransfer: false,
          storage: [{ name: 'pa_visit', type: 'cookie', purpose: 'counts a visit', duration: '24 hours' }],
        },
      ],
    },
  ],
  necessaryServices: [
    {
      name: 'Host Badge CDN',
      provider: 'Badge Ltd',
      privacyUrl: null,
      thirdCountryTransfer: false,
      storage: [{ name: 'badge.cache', type: 'localStorage', purpose: 'caches the badge', duration: 'a week' }],
    },
  ],
  essential: {
    storage: [
      {
        name: 'mc.progress.*',
        type: 'localStorage',
        purposeKey: 'consent.purpose.progress',
        durationKey: 'consent.duration.persistent',
        optional: true,
      },
      {
        name: 'mc.locale',
        type: 'localStorage',
        purposeKey: 'consent.purpose.locale',
        durationKey: 'consent.duration.persistent',
        optional: false,
      },
    ],
  },
  privacySlug: 'privacy',
};

const nothingGranted = () => false;
const analyticsGranted = (category: string) => category === 'analytics';

describe('Storage purge (§12.5)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('removes what a refused category stored, because withdrawal has to reach the data too', () => {
    localStorage.setItem('pa_visit', '1');
    localStorage.setItem('mc.locale', 'de');

    const removed = purgeUndeclared(PAYLOAD, nothingGranted);

    expect(removed.localStorage).toEqual(['pa_visit']);
    expect(localStorage.getItem('pa_visit')).toBeNull();
    expect(localStorage.getItem('mc.locale')).toBe('de');
  });

  it('leaves it alone once the visitor grants the category', () => {
    localStorage.setItem('pa_visit', '1');

    expect(purgeUndeclared(PAYLOAD, analyticsGranted).localStorage).toEqual([]);
    expect(localStorage.getItem('pa_visit')).toBe('1');
  });

  it('spares services declared necessary, which no decision could ever remove', () => {
    localStorage.setItem('badge.cache', '{}');

    expect(purgeUndeclared(PAYLOAD, nothingGranted).localStorage).toEqual([]);
    expect(localStorage.getItem('badge.cache')).toBe('{}');
  });

  it('removes keys nothing declared at all — the case blocking a write cannot catch', () => {
    // Whatever wrote this got past every guard the shell has, because in one JavaScript realm there is no
    // guard to get past. Deleting needs no cooperation from the writer.
    localStorage.setItem('sneaky.id', 'uuid');
    sessionStorage.setItem('sneaky.session', 'uuid');

    const removed = purgeUndeclared(PAYLOAD, analyticsGranted);

    expect(removed.localStorage).toEqual(['sneaky.id']);
    expect(removed.sessionStorage).toEqual(['sneaky.session']);
    expect(localStorage.getItem('sneaky.id')).toBeNull();
    expect(sessionStorage.getItem('sneaky.session')).toBeNull();
  });

  it('honours a wildcard, so every saved position is one declaration', () => {
    localStorage.setItem('mc.progress.ep1', '120');
    localStorage.setItem('mc.progress.ep2', '340');

    expect(purgeUndeclared(PAYLOAD, nothingGranted).localStorage).toEqual([]);
    expect(localStorage.getItem('mc.progress.ep2')).toBe('340');
  });

  it('deletes every doomed key even though removing while enumerating reindexes storage', () => {
    // The bug this pins: iterating a live Storage by index and removing as you go skips every other entry.
    ['a', 'b', 'c', 'd'].forEach((key) => localStorage.setItem(key, '1'));

    expect(purgeUndeclared(PAYLOAD, nothingGranted).localStorage.sort()).toEqual(['a', 'b', 'c', 'd']);
    expect(localStorage.length).toBe(0);
  });

  it('does nothing at all until the declaration is known', () => {
    localStorage.setItem('sneaky.id', 'uuid');

    // An API that failed or has not answered yet is not a declaration that everything is undeclared. Sweeping
    // on an empty payload would wipe the visitor's own settings every time the network hiccups.
    const removed = purgeUndeclared({ ...PAYLOAD, fingerprint: '' }, nothingGranted);

    expect(removed.localStorage).toEqual([]);
    expect(localStorage.getItem('sneaky.id')).toBe('uuid');
  });
});
