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

  it('maps the user, or null when anonymous', () => {
    expect(buildCtx(base).user).toBeNull();
    const user: MeView = { id: 'u1', displayName: 'U', avatarUrl: null, role: 'podcaster' };
    expect(buildCtx({ ...base, user }).user).toEqual({ id: 'u1', role: 'podcaster' });
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

  it('is a no-op rather than a throw when no router is above the mount', () => {
    // A plugin calls navigate inside its own render; an unwired host must not turn that into a crashed
    // tile. The SlotRegion boundary would catch it, but blanking a tile for a missing router is worse.
    expect(() => buildCtx(base).route.navigate('anywhere')).not.toThrow();
  });
});
