// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { LocaleInfo, PluginContext, Role, Scope, ThemeTokens } from '@mosaicast/plugin-sdk';

import { api } from '../api/client';
import type { MeView, ThemeTokenSet } from '../api/types';
import {
  makePluginApi,
  makePluginBlobs,
  makePluginDocs,
  makePluginFeeds,
  makePluginSchema,
  makePluginTags,
  makePluginNotify,
  makePluginUsers,
  makePluginTranslation,
} from './pluginApi';
import { storedPosition } from '../player/progress';
import { coreLinks } from './coreLinks';

/**
 * Assembles the {@link PluginContext} the host sets on a mounted plugin element (ARCHITECTURE §7.5). The shell
 * resolves the scope, the host-filtered `episodes`, the current `user`, the plugin's `api` client, the active
 * `locale`, the `theme` tokens, the visitor's `consent` decisions and — on a `/p/{pluginId}/…` page — the
 * `route` subpath. `filter` and `progress` are wired to the shell where cheap and stubbed where their full
 * mechanism lands later; the shell re-renders the element by reassigning `ctx` whenever these inputs change,
 * so `onChange` handlers are intentionally inert.
 *
 * `route.navigate` is the exception to that inertness and the only outbound handle here: a plugin cannot
 * describe where it wants to go by re-rendering, so this one has to actually do something (§6.4).
 */
export interface CtxInputs {
  pluginId: string;
  scope: Scope;
  episodes: string[];
  episodeLabels: Record<string, string>;
  user: MeView | null;
  theme: ThemeTokenSet | undefined;
  locale: string;
  /** The languages the shell can render in, from `/api/i18n/locales` (§12.7). */
  uiLocales: LocaleInfo[];
  /** The languages the admin permits content to be authored in — what a plugin's editor should offer. */
  contentLocales: LocaleInfo[];
  playerCurrentTime: () => number;
  playerSeekTo: (seconds: number) => void;
  /** The subpath below `/p/{pluginId}/` when the plugin is rendered as a deep-link page; else empty. */
  routePath?: string;
  /**
   * The query string of that page URL, leading `?` included or not — read back as `ctx.route.query`.
   *
   * Only a page mount has one. A card in a slot region sits on a core route whose query is the shell's
   * filter state, which is `ctx.filter`'s job to expose and not this one's.
   */
  routeQuery?: string;
  /** The fragment of that page URL, leading `#` optional — read back as `ctx.route.hash`. */
  routeHash?: string;
  /** Whether the plugin declares `storage.schema` — decides `ctx.schema` vs `null` (§7.6). */
  hasSchema?: boolean;
  /** Whether the plugin declares a `blobs` block — decides `ctx.blobs` vs `null` (§11). */
  hasBlobs?: boolean;
  /** Whether the plugin declares a `tags` block — decides `ctx.tags` vs `null` (§6.1). */
  hasTags?: boolean;
  /** Whether the plugin declares an `identity` block — decides `ctx.users` vs `null` (§8.8). */
  hasIdentity?: boolean;
  /** Whether the plugin declares a `notifications` block — decides `ctx.notify` vs `null` (§17.1). */
  hasNotifications?: boolean;
  /**
   * Whether the host granted a `ctx.translation` client (§16) — the manifest declared the kind *and* an
   * admin configured a provider. One flag rather than two on purpose: the SDK makes those two reasons for
   * `null` indistinguishable, so the shell is told the answer instead of reconstructing it.
   */
  hasTranslation?: boolean;
  /**
   * Navigates the shell to an absolute path. Supplied by {@link PluginMount} from the router; absent in
   * tests and in any mount with no router above it, where `navigate` degrades to a no-op rather than
   * throwing inside a plugin's render.
   */
  navigateTo?: (path: string, opts?: { replace?: boolean }) => void;
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

/**
 * The eight colours the SDK's `ThemeTokens` promises, and nothing else. The site payload carries more — the
 * clamped `accentText` (core#162) — which plugins read as `--mc-accent-text` through CSS; handing the extra
 * field over here would make it an undocumented part of `ctx.theme` that a plugin could come to rely on.
 */
function sdkTheme(theme: ThemeTokenSet | undefined): ThemeTokens {
  if (!theme) {
    return FALLBACK_THEME;
  }
  const { bg, surface, text, textMuted, accent, accentContrast, accent2, border } = theme;
  return { bg, surface, text, textMuted, accent, accentContrast, accent2, border };
}

export function buildCtx(inputs: CtxInputs): HostPluginContext {
  // Subscription handlers must hand back an unsubscribe (SDK 0.4.0). Where the shell has nothing to
  // subscribe to yet, this returns a no-op *unsubscribe* rather than nothing — a plugin calling it inside a
  // React effect uses the return value as the cleanup, so `undefined` would throw at unmount.
  const noUnsubscribe = () => () => {};
  return {
    scope: inputs.scope,
    episodes: inputs.episodes,
    episodeLabels: inputs.episodeLabels,
    // The visitor's own name and picture (§8.8). Not gated: telling a plugin who its own viewer is
    // discloses nothing the viewer does not already know. Learning about anyone *else* takes `users`
    // below, and a manifest declaration.
    user: inputs.user
      ? {
          id: inputs.user.id,
          role: inputs.user.role as Role,
          displayName: inputs.user.displayName,
          avatarUrl: inputs.user.avatarUrl,
        }
      : null,
    api: makePluginApi(inputs.pluginId),
    // Non-nullable, both of them: every plugin has a doc store, and `feeds` reads host data the same
    // visitor can already read from /api/episodes/* — neither is something a manifest declares (§7.5).
    docs: makePluginDocs(inputs.pluginId, inputs.user?.id ?? 'anonymous'),
    feeds: makePluginFeeds(inputs.pluginId),
    // Null for a doc-store plugin, mirroring the backend's `ctx.schema()`. Handing every plugin a client
    // would mean one that 404s on every call — a worse answer than saying there is nothing here.
    schema: inputs.hasSchema ? makePluginSchema(inputs.pluginId) : null,
    // Null unless the manifest declared file storage, for the same reason `schema` is: a client that 404s on
    // every call is a worse answer than saying there is nothing here (§11).
    blobs: inputs.hasBlobs ? makePluginBlobs(inputs.pluginId) : null,
    // Null unless the manifest declared a tag surface — the third repetition of the same rule, and for the
    // same reason: what a plugin may touch is decided in the manifest and nowhere else (§6.1).
    tags: inputs.hasTags ? makePluginTags(inputs.pluginId) : null,
    // Null unless the manifest declared `identity` — the same rule again, and the reason it is declared
    // rather than derived is that a plugin already holds user ids: what is granted here is turning them
    // into people (§8.8).
    users: inputs.hasIdentity ? makePluginUsers(inputs.pluginId) : null,
    // Null unless the manifest declared `notifications`. The block an operator most needs to read before
    // installing, because it is the only surface that writes into another user's view of the site (§17.1).
    notify: inputs.hasNotifications ? makePluginNotify(inputs.pluginId) : null,
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
    route: {
      path: inputs.routePath ?? '',
      // `navigate` has always accepted a `?query#hash` and there was no way to read back what it wrote, so
      // the only route to shareable filter or pagination state was `location.search` — which is the one
      // thing the navigate docs tell plugins not to reach for (§6.4).
      query: new URLSearchParams(inputs.routeQuery ?? ''),
      hash: (inputs.routeHash ?? '').replace(/^#/, ''),
      onChange: noUnsubscribe,
      navigate: (subpath: string, opts?: { replace?: boolean }) =>
        inputs.navigateTo?.(pluginPath(inputs.pluginId, subpath), opts),
    },
    // Pure string builders over the host's own routes. Not a capability — a plugin can already write any
    // href — but the URL shapes belong to the host that serves them (§6.4).
    links: coreLinks,
    // `available` and `content` are different lists on purpose (§12.7): a site can require a Dutch imprint
    // with an English-only UI, so an editor built from `available` would offer the wrong languages.
    locale: {
      current: () => inputs.locale,
      onChange: noUnsubscribe,
      available: () => inputs.uiLocales,
      content: () => inputs.contentLocales,
    },
    // Null unless the manifest declared the kind *and* this site has a provider — the fourth repetition of
    // the rule, with the one gate whose second half moves under a running plugin, which is why the SDK tells
    // authors to read `ctx.translation` at the point of use rather than caching the handle (§16).
    translation: inputs.hasTranslation ? makePluginTranslation(inputs.pluginId) : null,
    progress: {
      // Through the player's own reader, so the "remember playback position" switch holds here too.
      get: (episodeId: string) => Promise.resolve(storedPosition(episodeId)),
    },
    theme: sdkTheme(inputs.theme),
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

/**
 * The absolute path a plugin's `navigate(subpath)` resolves to, confined to `/p/{pluginId}/`.
 *
 * The confinement is the host's job, not the plugin's promise: a leading `/` is stripped so an absolute
 * target cannot escape the namespace, and `..` segments are dropped so a relative one cannot climb out of
 * it either. `.` segments go too, being noise. Query and hash survive — a plugin may legitimately carry
 * either — and are searched for only after the path is cleaned, so `..` inside a query string is left
 * alone.
 *
 * The result is that another plugin's route, or a core one, is not so much blocked as unnameable — the same
 * property the schema store has for tables.
 */
export function pluginPath(pluginId: string, subpath: string): string {
  const [rawPath = '', suffix = ''] = splitSuffix(subpath ?? '');
  const segments = rawPath
    .split('/')
    .filter((segment) => segment !== '' && segment !== '.' && segment !== '..');
  return `/p/${pluginId}${segments.length ? `/${segments.join('/')}` : ''}${suffix}`;
}

/** Splits a subpath into its path and its `?query#hash` tail, whichever comes first. */
function splitSuffix(subpath: string): [string, string] {
  const cut = Math.min(
    ...['?', '#'].map((mark) => (subpath.includes(mark) ? subpath.indexOf(mark) : subpath.length)),
  );
  return [subpath.slice(0, cut), subpath.slice(cut)];
}

/** Privilege ranks matching the backend `PluginAccessPolicy`; anonymous is 0. */
const RANK: Record<string, number> = { anonymous: 0, fan: 1, podcaster: 2, admin: 3 };

/** Whether a (possibly anonymous) user meets a slot's `visibleTo` floor — the shell hides what it can't show. */
export function canSeeSlot(visibleTo: string | null, role: Role | undefined): boolean {
  const floor = RANK[(visibleTo ?? 'anonymous').toLowerCase()] ?? RANK.podcaster;
  const have = role ? (RANK[role] ?? 0) : 0;
  return have >= floor;
}
