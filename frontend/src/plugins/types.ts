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
   * Whether the plugin declares a `tags` block. Decides `ctx.tags` vs `null`, the third repetition of the
   * rule `hasSchema` and `hasBlobs` already state (§6.1).
   */
  hasTags?: boolean;
  /** Whether the plugin declares an `identity` block (§8.8). */
  hasIdentity?: boolean;
  /** Whether the plugin declares a `notifications` block (§17.1). */
  hasNotifications?: boolean;
  /**
   * Whether it declared `tags.writesEpisodes` — the capability half. Public because it is one: what a plugin
   * may change about the site's own filter options and recommendations is not a secret from the visitor
   * looking at the result.
   */
  tagsWriteEpisodes?: boolean;
  /**
   * Whether the shell should hand this plugin a `ctx.translation` client (§16).
   *
   * **Not pure declaration**, unlike the three flags above: the host sets it when the manifest declares
   * `external.kinds: ['translation']` *and* an admin has configured a provider. The SDK makes those two
   * reasons for `null` deliberately indistinguishable, so the host collapses them rather than the shell
   * reconstructing them. It also goes stale like activation does — an admin removing the provider is picked
   * up on the next manifest fetch, which is why the SDK tells plugins not to cache the handle.
   *
   * Independent of the visitor's role: `external.usedBy` is enforced at the endpoint, so a below-floor
   * visitor holds a client whose `translate()` is a 403.
   */
  hasTranslation?: boolean;
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
  /** The closed set of values this field accepts, if it declares one. Empty means free-form. */
  options?: AdminConfigOption[];
}

/**
 * One choice in a config field's closed set.
 *
 * The label arrives exactly as the manifest wrote it — a plain string, or an object keyed by locale — and
 * is resolved here rather than on the server, which knows neither the language the operator is reading in
 * nor when they switch it.
 */
export interface AdminConfigOption {
  value: string | number | boolean;
  label?: string | Record<string, string>;
}

/**
 * What to show for one option in the reader's language.
 *
 * Falls back the way a partly translated manifest needs: the exact locale, then its base language so
 * `de-AT` finds a `de` label, then English, then any label that exists, and finally the raw value — so a
 * missing translation degrades to something readable instead of an empty row.
 */
export function optionLabel(option: AdminConfigOption, locale: string): string {
  const { label, value } = option;
  if (typeof label === 'string' && label.trim()) {
    return label;
  }
  if (label && typeof label === 'object') {
    const base = locale.split('-')[0];
    const candidates = [locale, base, 'en'];
    for (const key of candidates) {
      const found = label[key];
      if (typeof found === 'string' && found.trim()) {
        return found;
      }
    }
    const any = Object.values(label).find((v) => typeof v === 'string' && v.trim());
    if (any) {
      return any;
    }
  }
  return String(value);
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
  /** What the plugin declared about the instance's external services (§16); `null` when it declared none. */
  external: AdminExternal | null;
}

/**
 * A plugin's declared use of the instance's external services, as the admin API reports it.
 *
 * `usedBy` is the effective floor, not the raw manifest value: a plugin that omitted it reads as
 * `podcaster` here, which is what the host actually enforces.
 */
export interface AdminExternal {
  kinds: string[];
  usedBy: string;
}
