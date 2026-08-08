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

describe('Cookie sweep (§12.5)', () => {
  const clearCookies = () => {
    document.cookie
      .split(';')
      .map((pair) => pair.split('=')[0]?.trim() ?? '')
      .filter((name) => name !== '')
      .forEach((name) => {
        document.cookie = `${name}=; Max-Age=0; Path=/`;
        document.cookie = `${name}=; Max-Age=0`;
      });
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearCookies();
  });

  it('expires a declared cookie whose category the visitor refused', () => {
    document.cookie = 'pa_visit=1; Path=/';

    expect(purgeUndeclared(PAYLOAD, nothingGranted).cookies).toEqual(['pa_visit']);
    expect(document.cookie).not.toContain('pa_visit');
  });

  it('keeps it once the category is granted', () => {
    document.cookie = 'pa_visit=1; Path=/';

    expect(purgeUndeclared(PAYLOAD, analyticsGranted).cookies).toEqual([]);
    expect(document.cookie).toContain('pa_visit=1');
  });

  it('leaves undeclared cookies alone — they may belong to another app on the same domain', () => {
    // The rule that applies to localStorage cannot apply here. `document.cookie` on podcasts.example.com also
    // shows what example.com set for the whole estate: an SSO session, a load-balancer affinity cookie, an
    // operator's own tag. Expiring those is not enforcing consent, it is breaking someone else's application.
    document.cookie = 'AWSALB=sticky; Path=/';
    document.cookie = 'sso_session=abc; Path=/';

    const removed = purgeUndeclared(PAYLOAD, nothingGranted);

    expect(removed.cookies).toEqual([]);
    expect(document.cookie).toContain('AWSALB=sticky');
    expect(document.cookie).toContain('sso_session=abc');
  });

  it('never writes a Domain wider than the current host', () => {
    // A parent-domain expiry is exactly the operation that reaches sibling subdomains, and the registrable
    // domain cannot be derived from a hostname without the public-suffix list anyway.
    const written: string[] = [];
    const real = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => 'pa_visit=1',
      set: (value: string) => written.push(value),
    });

    try {
      purgeUndeclared(PAYLOAD, nothingGranted);
    } finally {
      delete (document as unknown as Record<string, unknown>).cookie;
      if (real) {
        Object.defineProperty(Document.prototype, 'cookie', real);
      }
    }

    expect(written.length).toBeGreaterThan(0);
    written.forEach((value) => {
      expect(value).not.toMatch(/Domain=\./);
    });
  });

  it('never sweeps the consent decision itself', () => {
    document.cookie = 'mc_consent=analytics; Path=/';

    expect(purgeUndeclared(PAYLOAD, nothingGranted).cookies).toEqual([]);
    expect(document.cookie).toContain('mc_consent=analytics');
  });
});

describe('Malformed declarations (§12.5)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('survives a storage item with no name instead of taking the sweep down with it', () => {
    // A manifest parses into an all-optional record, so an author who wrote "key" where the schema says "name"
    // produces a null here. Letting it reach the allow-list threw a TypeError out of an effect mounted above
    // the router's boundary — a blank page for the visitor, and enforcement silently off.
    const broken = structuredClone(PAYLOAD) as ConsentPayload;
    (broken.categories[0]!.services[0]!.storage as unknown[]) = [
      { type: 'cookie', purpose: 'x', duration: 'y' },
      { name: 'pa_visit', type: 'cookie', purpose: 'counts a visit', duration: '24 hours' },
    ];
    localStorage.setItem('pa_visit', '1');
    localStorage.setItem('sneaky.id', 'uuid');

    const removed = purgeUndeclared(broken, analyticsGranted);

    // The nameless item authorises nothing; the well-formed one beside it still does its job.
    expect(removed.localStorage).toEqual(['sneaky.id']);
    expect(localStorage.getItem('pa_visit')).toBe('1');
  });

  it('refuses a bare "*", which would otherwise switch the sweep off for the whole origin', () => {
    // `"*"` reduces the prefix match to key.startsWith(''), true of every key on the origin — one manifest line
    // disabling enforcement and the storage audit that shares the predicate, for core's keys as much as its own.
    const wildcard = structuredClone(PAYLOAD) as ConsentPayload;
    wildcard.categories[0]!.services[0]!.storage = [
      { name: '*', type: 'localStorage', purpose: 'everything', duration: 'forever' },
    ];
    localStorage.setItem('sneaky.id', 'uuid');

    expect(purgeUndeclared(wildcard, analyticsGranted).localStorage).toEqual(['sneaky.id']);
    expect(localStorage.getItem('sneaky.id')).toBeNull();
  });
});
