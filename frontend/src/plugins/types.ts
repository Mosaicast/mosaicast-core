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
  /**
   * Credit, as the plugin declared it — all optional, all shown on `/about`.
   *
   * Never validated by the host: a plugin written before these existed, or one that spells its licence
   * oddly, is still a working plugin. So every one of them can be absent and the About page renders what
   * it has rather than asserting what it wants.
   */
  license?: string | null;
  author?: string | null;
  homepage?: string | null;
  /**
   * Whoever the plugin credits beyond its author — a data source, an upstream library, an artist. Separate
   * from `homepage` because "where this lives" and "who deserves credit" are not the same link.
   */
  attribution?: string | null;
}

/**
 * One entry in the shell's navigation menu, as `GET /api/plugins/navigation` resolved it.
 *
 * Already filtered to the caller and already ordered — the host applies the `visibleTo` floor and the
 * admin's decisions, so an anonymous visitor is never sent a podcaster-only entrance to hide client-side.
 */
export interface NavItem {
  pluginId: string;
  /** The subpath below `/p/{pluginId}/`; empty for the plugin's own root. */
  path: string;
  /** The href to link to, built by the host — never assembled from `pluginId` + `path` in the shell. */
  href: string;
  label: string;
  /**
   * A published `--mc-icon-*` name without the prefix, or null. Not validated by the host: an unknown name
   * falls back when rendered, and must be treated as untrusted input (see `NavMenu`).
   */
  icon: string | null;
}

/** One entry as the admin edits it — every declared entry, including ones currently switched off. */
export interface AdminNavItem extends NavItem {
  pluginName: string;
  visibleTo: string | null;
  enabled: boolean;
  order: number;
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
/**
 * A plugin's storage situation for the admin form (§11.1) — `null` when the plugin declares no `blobs`
 * block, and for a PODCASTER, who cannot edit these and has no use for the install's disk numbers.
 */
export interface AdminBlobs {
  usedBytes: number;
  fileCount: number;
  /** What is in force, whichever source it came from. */
  quotaBytes: number;
  maxFileBytes: number;
  /** Whether it came from an admin grant rather than the manifest or the operator default. */
  quotaOverridden: boolean;
  maxFileOverridden: boolean;
  /** What the manifest asked for, or null. */
  declaredQuotaBytes: number | null;
  declaredMaxFileBytes: number | null;
  /** The most an admin may grant here; null when the operator set no bound. */
  hardQuotaBytes: number | null;
  hardMaxFileBytes: number | null;
}

export interface AdminPlugin {
  id: string;
  status: 'LOADED' | 'DISABLED' | 'REJECTED';
  reason: string | null;
  name: string | null;
  version: string | null;
  enabled: boolean;
  config: Record<string, AdminConfigField>;
  consent: { services: ConsentServiceDeclaration[] | null } | null;
  blobs: AdminBlobs | null;
}
