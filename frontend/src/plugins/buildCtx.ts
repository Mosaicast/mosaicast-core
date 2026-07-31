// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { PluginContext, Role, Scope, ThemeTokens } from '@mosaicast/plugin-sdk';

import { api } from '../api/client';
import type { MeView, ThemeTokenSet } from '../api/types';
import { makePluginApi } from './pluginApi';

/**
 * Assembles the {@link PluginContext} the host sets on a mounted plugin element (ARCHITECTURE §7.5). The shell
 * resolves the scope, the host-filtered `episodes`, the current `user`, the plugin's `api` client, the active
 * `locale`, the `theme` tokens, the visitor's `consent` decisions and — on a `/p/{pluginId}/…` page — the
 * `route` subpath. `filter` and `progress` are wired to the shell where cheap and stubbed where their full
 * mechanism lands later; the shell re-renders the element by reassigning `ctx` whenever these inputs change,
 * so `onChange` handlers are intentionally inert.
 */
export interface CtxInputs {
  pluginId: string;
  scope: Scope;
  episodes: string[];
  episodeLabels: Record<string, string>;
  user: MeView | null;
  theme: ThemeTokenSet | undefined;
  locale: string;
  playerCurrentTime: () => number;
  playerSeekTo: (seconds: number) => void;
  /** The subpath below `/p/{pluginId}/` when the plugin is rendered as a deep-link page; else empty. */
  routePath?: string;
  /** Whether the visitor granted a consent category (§12.5); defaults to deny when absent. */
  consentHas?: (category: string) => boolean;
  /** Every granted category, for `ctx.consent.granted()`. */
  consentGranted?: () => string[];
  /** Opens the host's settings for one category and resolves with the visitor's answer. */
  consentRequest?: (category: string) => Promise<boolean>;
  /** Subscribes to consent changes; returns the unsubscribe the SDK contract requires. */
  consentSubscribe?: (listener: () => void) => () => void;
}

/**
 * The context the host sets on a plugin element. `episodeLabels` used to be bolted on here; the SDK
 * formalised it in 0.4.0, so this is now plain {@link PluginContext}.
 */
export type HostPluginContext = PluginContext;

const FALLBACK_THEME: ThemeTokens = {
  bg: '#ffffff',
  surface: '#ffffff',
  text: '#1c1a17',
  textMuted: '#6b6459',
  accent: '#c8553d',
  accentContrast: '#fff8f2',
  accent2: '#3d7d8c',
  border: '#e7ddcf',
};

export function buildCtx(inputs: CtxInputs): HostPluginContext {
  // Subscription handlers must hand back an unsubscribe (SDK 0.4.0). Where the shell has nothing to
  // subscribe to yet, this returns a no-op *unsubscribe* rather than nothing — a plugin calling it inside a
  // React effect uses the return value as the cleanup, so `undefined` would throw at unmount.
  const noUnsubscribe = () => () => {};
  return {
    scope: inputs.scope,
    episodes: inputs.episodes,
    episodeLabels: inputs.episodeLabels,
    user: inputs.user ? { id: inputs.user.id, role: inputs.user.role as Role } : null,
    api: makePluginApi(inputs.pluginId),
    // Default deny: a plugin must not get third-party permission the visitor never gave (§12.5).
    consent: {
      // Default deny: a plugin must not get third-party permission the visitor never gave (§12.5).
      has: (category: string) => inputs.consentHas?.(category) ?? false,
      granted: () => inputs.consentGranted?.() ?? [],
      request: (category: string) => inputs.consentRequest?.(category) ?? Promise.resolve(false),
      onChange: (cb: () => void) => inputs.consentSubscribe?.(cb) ?? (() => {}),
    },
    filter: { current: () => ({}), onChange: noUnsubscribe },
    player: {
      currentTime: inputs.playerCurrentTime,
      seekTo: inputs.playerSeekTo,
      on: noUnsubscribe,
    },
    route: { path: inputs.routePath ?? '', onChange: noUnsubscribe },
    locale: { current: () => inputs.locale, onChange: noUnsubscribe },
    progress: {
      get: (episodeId: string) => {
        const stored = localStorage.getItem(`mc.progress.${episodeId}`);
        return Promise.resolve(stored != null ? Number(stored) : null);
      },
    },
    theme: inputs.theme ?? FALLBACK_THEME,
    /**
     * Plugin-reported entries go to the host's log endpoint, which is what the admin viewer reads. Failures
     * are swallowed on purpose: a plugin trying to report a problem must never turn that into a second,
     * louder problem in the visitor's browser.
     */
    log: (level, message) => {
      void api
        .post(`/api/plugins/${inputs.pluginId}/log`, { level: level.toUpperCase(), message })
        .catch(() => {});
    },
  };
}

/** Privilege ranks matching the backend `PluginAccessPolicy`; anonymous is 0. */
const RANK: Record<string, number> = { anonymous: 0, fan: 1, podcaster: 2, admin: 3 };

/** Whether a (possibly anonymous) user meets a slot's `visibleTo` floor — the shell hides what it can't show. */
export function canSeeSlot(visibleTo: string | null, role: Role | undefined): boolean {
  const floor = RANK[(visibleTo ?? 'anonymous').toLowerCase()] ?? RANK.podcaster;
  const have = role ? (RANK[role] ?? 0) : 0;
  return have >= floor;
}
