// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

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
    scope: { type: 'episode', id: 'ep-1' },
    episodes: ['ep-1', 'ep-2'],
    user: null,
    theme: undefined,
    locale: 'de',
    playerCurrentTime: () => 12,
    playerSeekTo: () => {},
  };

  it('exposes scope, episodes, locale and an api client', () => {
    const ctx = buildCtx(base);
    expect(ctx.scope).toEqual({ type: 'episode', id: 'ep-1' });
    expect(ctx.episodes).toEqual(['ep-1', 'ep-2']);
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
});
