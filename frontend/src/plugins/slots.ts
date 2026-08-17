// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { Role, Scope } from '@mosaicast/plugin-sdk';

import { canSeeSlot } from './buildCtx';
import type { PublicPlugin } from './types';

/** One plugin element to mount in a slot region, with its stack order. */
export interface SlotMount {
  key: string;
  pluginId: string;
  element: string;
  order: number;
  /** Carried from the manifest so the mount can decide `ctx.schema` without another registry lookup. */
  hasSchema: boolean;
}

/**
 * Selects the plugin elements to mount in a given region (ARCHITECTURE §7.3): every slot whose `placement`
 * and `scope` level match, visible to the current role, sorted by `order` (ties broken by plugin id). Pure,
 * so the mounting policy is unit-testable without the DOM.
 */
export function selectMounts(
  plugins: PublicPlugin[],
  placement: string,
  scopeType: Scope['type'],
  role: Role | undefined,
): SlotMount[] {
  const mounts: SlotMount[] = [];
  for (const plugin of plugins) {
    for (const slot of plugin.slots ?? []) {
      if (slot.placement === placement && slot.scope === scopeType && canSeeSlot(slot.visibleTo, role)) {
        mounts.push({
          key: `${plugin.id}:${slot.element}`,
          pluginId: plugin.id,
          element: slot.element,
          order: slot.order ?? Number.MAX_SAFE_INTEGER,
          hasSchema: plugin.hasSchema ?? false,
        });
      }
    }
  }
  return mounts.sort((a, b) => a.order - b.order || a.pluginId.localeCompare(b.pluginId));
}
