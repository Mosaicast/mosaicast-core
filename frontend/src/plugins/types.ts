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
  /**
   * Whether the plugin declares `storage.schema`. Decides whether the shell hands it a `ctx.schema` client
   * or `null` — the frontend mirror of the backend's `ctx.schema()` being `null` for a doc-store plugin.
   */
  hasSchema?: boolean;
  /**
   * Whether the plugin declares a `blobs` block. Decides `ctx.blobs` vs `null`, the same way `hasSchema`
   * decides `ctx.schema` (§11). The declared limits are deliberately absent: they are what the plugin
   * asked for, this install may grant less, and the quota endpoint is the only honest source.
   */
  hasBlobs?: boolean;
}

/** One declared config field with the value currently in effect — a row of the generated admin form. */
export interface AdminConfigField {
  type: 'string' | 'number' | 'boolean';
  editableBy: 'admin' | 'podcaster';
  /** The manifest default; what the field falls back to when the override is cleared. */
  defaultValue: string | number | boolean | null;
  /** The effective value: the admin override when there is one, otherwise the default. */
  value: string | number | boolean | null;
  overridden: boolean;
}

/**
 * One third-party service a plugin declares (`PluginManifest.Service`, ARCHITECTURE §12.5). `hosts` doubles
 * as the CSP allow-list, so an origin missing here stays blocked even once the visitor consents.
 */
export interface ConsentServiceDeclaration {
  id: string | null;
  name: string;
  provider: string | null;
  category: string;
  privacyUrl: string | null;
  hosts: string[] | null;
  thirdCountryTransfer: boolean | null;
  storage: { name: string; type: string; purpose: string; duration: string }[] | null;
}

/**
 * A discovered plugin's load state plus its host settings, `GET /api/admin/plugins`.
 *
 * `status` is the boot outcome, `enabled` the current switch: `DISABLED` means it was already off when the
 * host booted, while `LOADED` + `enabled: false` means an admin switched it off since — its backend keeps
 * running until the next restart, though every surface it serves is already closed.
 */
export interface AdminPlugin {
  id: string;
  status: 'LOADED' | 'DISABLED' | 'REJECTED';
  reason: string | null;
  name: string | null;
  version: string | null;
  enabled: boolean;
  config: Record<string, AdminConfigField>;
  consent: { services: ConsentServiceDeclaration[] | null } | null;
}
