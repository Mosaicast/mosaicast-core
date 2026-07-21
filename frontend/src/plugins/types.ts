// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { Scope } from '@mosaicast/plugin-sdk';

/**
 * The client-facing plugin descriptors the shell mounts (ARCHITECTURE §7.3/§7.5). Mirrors the backend
 * `PluginManifestController.PublicPlugin` / `AdminPluginController.AdminPlugin` JSON exactly.
 */

/** One mount point declared by a plugin. */
export interface PluginSlot {
  scope: Scope['type'];
  element: string;
  placement: string;
  visibleTo: string | null;
  order: number | null;
}

/** A loaded plugin as exposed to the shell by `GET /api/plugins/manifest`. */
export interface PublicPlugin {
  id: string;
  name: string;
  version: string;
  frontend: { entry: string; elements: string[] } | null;
  slots: PluginSlot[] | null;
}

/** A discovered plugin's load state, `GET /api/admin/plugins`. */
export interface AdminPlugin {
  id: string;
  status: 'LOADED' | 'REJECTED';
  reason: string | null;
  name: string | null;
  version: string | null;
}
