// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { describe, expect, it } from 'vitest';

import { selectMounts } from './slots';
import type { PublicPlugin, PluginSlot } from './types';

/** Selection policy for plugin slot regions (ARCHITECTURE §7.3). */
describe('selectMounts', () => {
  const plugin = (id: string, slots: PluginSlot[]): PublicPlugin => ({
    id,
    name: id,
    version: '1.0.0',
    frontend: { entry: 's.js', elements: [] },
    slots,
  });

  const cardSlot: PluginSlot = {
    scope: 'episode',
    element: 'p-card',
    placement: 'main',
    visibleTo: 'anonymous',
    order: 100,
  };
  const adminSlot: PluginSlot = {
    scope: 'site',
    element: 'p-admin',
    placement: 'sidebar',
    visibleTo: 'podcaster',
    order: null,
  };

  it('matches on placement and scope level', () => {
    const plugins = [plugin('sample', [cardSlot, adminSlot])];
    expect(selectMounts(plugins, 'main', 'episode', undefined)).toHaveLength(1);
    expect(selectMounts(plugins, 'main', 'site', undefined)).toHaveLength(0);
    expect(selectMounts(plugins, 'sidebar', 'episode', 'podcaster')).toHaveLength(0);
  });

  it('gates by visibleTo', () => {
    const plugins = [plugin('sample', [adminSlot])];
    expect(selectMounts(plugins, 'sidebar', 'site', undefined)).toHaveLength(0);
    expect(selectMounts(plugins, 'sidebar', 'site', 'fan')).toHaveLength(0);
    expect(selectMounts(plugins, 'sidebar', 'site', 'podcaster')).toHaveLength(1);
    expect(selectMounts(plugins, 'sidebar', 'site', 'admin')).toHaveLength(1);
  });

  it('stacks by order then plugin id', () => {
    const slot = (order: number | null): PluginSlot => ({
      scope: 'site',
      element: 'e',
      placement: 'site',
      visibleTo: 'anonymous',
      order,
    });
    const plugins = [
      plugin('b', [slot(10)]),
      plugin('a', [slot(10)]),
      plugin('c', [slot(5)]),
      plugin('z', [slot(null)]), // no order → last
    ];
    expect(selectMounts(plugins, 'site', 'site', undefined).map((m) => m.pluginId)).toEqual([
      'c',
      'a',
      'b',
      'z',
    ]);
  });
});
