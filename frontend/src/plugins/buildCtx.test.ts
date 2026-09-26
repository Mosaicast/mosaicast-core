// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it, vi } from 'vitest';

import type { MeView } from '../api/types';
import { buildCtx, canSeeSlot, type CtxInputs } from './buildCtx';

describe('canSeeSlot', () => {
  it('lets everyone see an anonymous slot', () => {
    expect(canSeeSlot('anonymous', undefined)).toBe(true);
    expect(canSeeSlot(null, undefined)).toBe(true);
  });

  it('requires the role floor for gated slots', () => {
    expect(canSeeSlot('podcaster', undefined)).toBe(false);
    expect(canSeeSlot('podcaster', 'fan')).toBe(false);
    expect(canSeeSlot('podcaster', 'podcaster')).toBe(true);
    expect(canSeeSlot('podcaster', 'admin')).toBe(true);
    expect(canSeeSlot('admin', 'podcaster')).toBe(false);
  });
});

describe('buildCtx', () => {
  const base: CtxInputs = {
    pluginId: 'sample',
    scope: { type: 'episode', id: 'the-cast-s01e01' },
    episodes: ['the-cast-s01e01', 'the-cast-s01e02'],
    episodeLabels: { 'the-cast-s01e01': 'S01E01 · One' },
    user: null,
    theme: undefined,
    locale: 'de',
    uiLocales: [{ code: 'en', nativeName: 'English', isDefault: true }],
    contentLocales: [{ code: 'en', nativeName: 'English', isDefault: true }],
    playerCurrentTime: () => 12,
    playerSeekTo: () => {},
  };

  it('exposes scope, episodes, locale and an api client', () => {
    const ctx = buildCtx(base);
    expect(ctx.scope).toEqual({ type: 'episode', id: 'the-cast-s01e01' });
    expect(ctx.episodes).toEqual(['the-cast-s01e01', 'the-cast-s01e02']);
    expect(ctx.episodeLabels?.['the-cast-s01e01']).toBe('S01E01 · One');
    expect(ctx.locale.current()).toBe('de');
    expect(typeof ctx.api.get).toBe('function');
    expect(typeof ctx.api.put).toBe('function');
  });

  it('hands over the two language lists separately (§12.7)', () => {
    // The asymmetry is the point: a site can require a Dutch imprint with an English-only shell, so a
    // plugin editor built from `available()` would offer the wrong languages.
    const ctx = buildCtx({
      ...base,
      uiLocales: [{ code: 'en', nativeName: 'English', isDefault: true }],
      contentLocales: [
        { code: 'en', nativeName: 'English', isDefault: true },
        { code: 'nl', nativeName: 'Nederlands', isDefault: false },
      ],
    });

    expect(ctx.locale.available().map((l) => l.code)).toEqual(['en']);
    expect(ctx.locale.content().map((l) => l.code)).toEqual(['en', 'nl']);
    expect(ctx.locale.content()[1].nativeName).toBe('Nederlands');
  });

  it('has no translator, because core does not implement one yet', () => {
    // `null` is the SDK's value for "this site does not do that", and it is also what an operator who
    // configures no provider produces permanently — so a plugin must handle it either way.
    expect(buildCtx(base).translation).toBeNull();
  });

  it('maps the user, or null when anonymous', () => {
    expect(buildCtx(base).user).toBeNull();
    const user: MeView = {
      id: 'u1',
      displayName: 'U',
      avatarUrl: '/api/users/u1/avatar',
      avatarProvider: null,
      role: 'podcaster',
    };
    // The visitor's own name and picture ride along since §8.8 — telling a plugin who its own viewer is
    // discloses nothing the viewer does not already know. `avatarProvider` deliberately does not: which
    // account supplies the picture is the user's business, not the plugin's.
    expect(buildCtx({ ...base, user }).user).toEqual({
      id: 'u1',
      role: 'podcaster',
      displayName: 'U',
      avatarUrl: '/api/users/u1/avatar',
    });
  });

  it('hands over ctx.users only when the manifest declared identity (§8.8)', () => {
    expect(buildCtx(base).users).toBeNull();
    expect(buildCtx({ ...base, hasIdentity: true }).users).not.toBeNull();
  });

  it('hands over the clamped text accent with the other tokens (SDK 0.16.0)', () => {
    const tokens = {
      bg: '#fffef0', surface: '#ffffff', text: '#1c1a17', textMuted: '#6b6459', accent: '#fff176',
      accentContrast: '#1c1a17', accentText: '#6b5f00', accent2: '#3d7d8c', border: '#e7ddcf',
    };
    // The seed is too pale to read as text; the clamped value is what a plugin colours links with.
    expect(buildCtx({ ...base, theme: tokens }).theme.accentText).toBe('#6b5f00');
    expect(buildCtx({ ...base, theme: tokens }).theme.accent).toBe('#fff176');
  });

  it('sanitizes with the shell’s own feed-HTML policy (SDK 0.16.0)', () => {
    const { sanitize } = buildCtx(base);
    // The payload that defaced the wiki plugin under DOMPurify's defaults.
    expect(sanitize('<style>:host{position:fixed}</style><p style="position:fixed">x</p>')).toBe('<p>x</p>');
    expect(sanitize(null)).toBe('');
  });

  it('falls back to default theme tokens when none provided', () => {
    expect(buildCtx(base).theme.accent).toMatch(/^#/);
  });

  it('hands back an unsubscribe from every subscription (SDK 0.4.0)', () => {
    // A plugin calls these inside a React effect and uses the result as the cleanup, so returning
    // undefined would throw at unmount rather than at compile time.
    const ctx = buildCtx(base);

    expect(typeof ctx.consent.onChange(() => {})).toBe('function');
    expect(typeof ctx.filter.onChange(() => {})).toBe('function');
    expect(typeof ctx.route.onChange(() => {})).toBe('function');
    expect(typeof ctx.locale.onChange(() => {})).toBe('function');
    expect(typeof ctx.player.on('play', () => {})).toBe('function');
  });

  it('denies consent by default and reports nothing granted', async () => {
    const ctx = buildCtx(base);

    expect(ctx.consent.has('analytics')).toBe(false);
    expect(ctx.consent.granted()).toEqual([]);
    // Without a host implementation a request cannot silently succeed.
    await expect(ctx.consent.request('analytics')).resolves.toBe(false);
  });

  it('routes consent calls to the host when wired', async () => {
    const ctx = buildCtx({
      ...base,
      consentHas: (c) => c === 'analytics',
      consentGranted: () => ['analytics'],
      consentRequest: () => Promise.resolve(true),
      consentSubscribe: (listener) => {
        listener();
        return () => {};
      },
    });

    expect(ctx.consent.has('analytics')).toBe(true);
    expect(ctx.consent.granted()).toEqual(['analytics']);
    await expect(ctx.consent.request('analytics')).resolves.toBe(true);
  });

  it('posts ctx.log to the host log endpoint', async () => {
    const calls: { url: string; body: string | undefined }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        calls.push({ url, body: init?.body as string | undefined });
        return Promise.resolve({ ok: true, status: 204, text: () => Promise.resolve('') });
      }),
    );

    buildCtx(base).log('warn', 'the widget could not load');

    await vi.waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].url).toBe('/api/plugins/sample/log');
    // The endpoint parses levels case-insensitively; the SDK's type is lower-case.
    expect(calls[0].body).toContain('"level":"WARN"');
    vi.unstubAllGlobals();
  });

  it('hands a doc-store plugin no schema client at all', () => {
    // Mirrors the backend's ctx.schema() being null: a client that 404s on every call would be a worse
    // answer than saying there is nothing here, and the SDK type makes the plugin handle it.
    expect(buildCtx(base).schema).toBeNull();

    const schema = buildCtx({ ...base, hasSchema: true }).schema;
    expect(typeof schema?.search).toBe('function');
    expect(typeof schema?.count).toBe('function');
  });

  it('hands a plugin that stores no files no blob client at all', () => {
    // Same shape as `schema`, and it matters more here: `blobs` is the newer member, so a component written
    // against a context that always has one would break on every plugin that declared no `blobs` block.
    expect(buildCtx(base).blobs).toBeNull();

    const blobs = buildCtx({ ...base, hasBlobs: true }).blobs;
    expect(typeof blobs?.upload).toBe('function');
    // Derived from the ref, and pointed at the host's own origin — no CSP host, no consent decision.
    expect(blobs?.urlFor('abc')).toBe('/api/plugins/sample/blob/abc');
  });

  it('builds core links without granting a way to navigate to them', () => {
    const { links } = buildCtx(base);

    expect(links.episode('kraken')).toBe('/episodes/kraken');
    expect(links.episode('kraken', { t: 724 })).toBe('/episodes/kraken?t=724');
    // Zero is the start, which a bare link already means — carrying `?t=0` would make one moment two URLs.
    expect(links.episode('kraken', { t: 0 })).toBe('/episodes/kraken');
    expect(links.feed('main', { season: '2' })).toBe('/feeds/main?season=2');
    // `newest` is the shell's fallback and what SiteUrls canonicalizes to, so it is never carried.
    expect(links.feed('main', { order: 'newest' })).toBe('/feeds/main');
    expect(links.feed('main', { order: 'oldest' })).toBe('/feeds/main?order=oldest');
    expect(links.episode('a b/c')).toBe('/episodes/a%20b%2Fc');
  });

  it('confines navigate to the plugin’s own subtree', () => {
    const targets: { path: string; replace?: boolean }[] = [];
    const ctx = buildCtx({ ...base, navigateTo: (path, opts) => targets.push({ path, ...opts }) });

    ctx.route.navigate('glossary/kraken');
    ctx.route.navigate('index', { replace: true });

    expect(targets).toEqual([
      { path: '/p/sample/glossary/kraken' },
      { path: '/p/sample/index', replace: true },
    ]);
  });

  it('cannot be aimed at another plugin or at core', () => {
    const targets: string[] = [];
    const ctx = buildCtx({ ...base, navigateTo: (path) => targets.push(path) });

    ctx.route.navigate('/p/other/secret');       // a leading slash does not escape the namespace
    ctx.route.navigate('../../admin');           // nor does climbing
    ctx.route.navigate('a/../../b');             // including mid-path
    ctx.route.navigate('');                      // the plugin's own root

    expect(targets).toEqual([
      '/p/sample/p/other/secret',
      '/p/sample/admin',
      '/p/sample/a/b',
      '/p/sample',
    ]);
  });

  it('keeps a query and hash the plugin carries', () => {
    const targets: string[] = [];
    const ctx = buildCtx({ ...base, navigateTo: (path) => targets.push(path) });

    ctx.route.navigate('page?tab=history#top');
    // `..` inside the query is text, not a segment, so cleaning the path leaves it alone.
    ctx.route.navigate('page?next=../x');

    expect(targets).toEqual(['/p/sample/page?tab=history#top', '/p/sample/page?next=../x']);
  });

  it('hands a plugin with no tags block no tags client at all', () => {
    // The third repetition of the schema/blobs rule, and the reason it is a rule: what a plugin may touch
    // is decided in the manifest, so a client that 403s on every call would say the wrong thing.
    expect(buildCtx(base).tags).toBeNull();

    const tags = buildCtx({ ...base, hasTags: true }).tags;
    expect(typeof tags?.all).toBe('function');
    expect(typeof tags?.tagEpisode).toBe('function');
  });

  it('hands a plugin no translation client unless the host granted one', () => {
    // One flag for two conditions, and that is the contract rather than a shortcut: the SDK makes "your
    // manifest did not ask" and "this site has no provider" deliberately indistinguishable, so the host
    // collapses them and the shell is told the answer.
    expect(buildCtx(base).translation).toBeNull();

    const translation = buildCtx({ ...base, hasTranslation: true }).translation;
    expect(typeof translation?.translate).toBe('function');
    // Non-null is not permission: `external.usedBy` is enforced at the endpoint, so this can still 403.
    expect(translation?.available()).toBe(true);
  });

  it('always has a doc client and a feeds client', () => {
    // Neither is declared in a manifest: every plugin has a doc store, and `feeds` reads host data the same
    // visitor can already read from /api/episodes/*.
    const ctx = buildCtx(base);
    expect(typeof ctx.docs.get).toBe('function');
    expect(typeof ctx.feeds.displayMany).toBe('function');
  });

  it('exposes the page URL’s query and hash, and only on a page mount', () => {
    const onPage = buildCtx({ ...base, routePath: 'glossary', routeQuery: '?tab=history&page=2', routeHash: '#top' });

    expect(onPage.route.query.get('tab')).toBe('history');
    expect(onPage.route.query.get('page')).toBe('2');
    // Without the '#': a plugin writing `#${ctx.route.hash}` should not produce '##top'.
    expect(onPage.route.hash).toBe('top');

    // A card in a slot region sits on a core route whose query is the shell's filter state — `ctx.filter`'s
    // business, not this one's.
    const inSlot = buildCtx(base);
    expect([...inSlot.route.query.keys()]).toEqual([]);
    expect(inSlot.route.hash).toBe('');
  });

  it('is a no-op rather than a throw when no router is above the mount', () => {
    // A plugin calls navigate inside its own render; an unwired host must not turn that into a crashed
    // tile. The SlotRegion boundary would catch it, but blanking a tile for a missing router is worse.
    expect(() => buildCtx(base).route.navigate('anywhere')).not.toThrow();
  });

  it('hands a plugin no stored position once remembering is switched off (core#201)', async () => {
    localStorage.setItem('mc.progress.e1', '754');
    try {
      expect(await buildCtx(base).progress.get('e1')).toBe(754);

      localStorage.setItem('mc.prefs.progress', 'off');
      // It read the key directly, so the position still came back after the visitor said not to keep it.
      expect(await buildCtx(base).progress.get('e1')).toBeNull();
    } finally {
      localStorage.removeItem('mc.progress.e1');
      localStorage.removeItem('mc.prefs.progress');
    }
  });
});
