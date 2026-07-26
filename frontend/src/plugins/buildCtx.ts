// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { PluginContext, Role, Scope, ThemeTokens } from '@mosaicast/plugin-sdk';

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
}

/**
 * The context the host sets on a plugin element: the SDK `PluginContext` plus `episodeLabels` (slug → human
 * label), which formalizes into the SDK type in a later release; until then the host provides it directly.
 */
export type HostPluginContext = PluginContext & { episodeLabels: Record<string, string> };

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
  const noop = () => {};
  return {
    scope: inputs.scope,
    episodes: inputs.episodes,
    episodeLabels: inputs.episodeLabels,
    user: inputs.user ? { id: inputs.user.id, role: inputs.user.role as Role } : null,
    api: makePluginApi(inputs.pluginId),
    // Default deny: a plugin must not get third-party permission the visitor never gave (§12.5).
    consent: { has: (category: string) => inputs.consentHas?.(category) ?? false, onChange: noop },
    filter: { current: () => ({}), onChange: noop },
    player: {
      currentTime: inputs.playerCurrentTime,
      seekTo: inputs.playerSeekTo,
      on: noop,
    },
    route: { path: inputs.routePath ?? '', onChange: noop },
    locale: { current: () => inputs.locale, onChange: noop },
    progress: {
      get: (episodeId: string) => {
        const stored = localStorage.getItem(`mc.progress.${episodeId}`);
        return Promise.resolve(stored != null ? Number(stored) : null);
      },
    },
    theme: inputs.theme ?? FALLBACK_THEME,
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
