// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';

import type { Scope } from '@mosaicast/plugin-sdk';

import { useUser } from '../auth/UserContext';
import { SlotFailed } from '../components/SlotFailed';
import { useConsent } from '../consent/ConsentContext';
import { contentLocaleInfos, uiLocaleInfos } from '../i18n';
import { usePlayerActions } from '../player/PlayerContext';
import { useSite } from '../theme/SiteContext';
import { buildCtx } from './buildCtx';

/**
 * Mounts one plugin custom element (ARCHITECTURE §7.5). Waits for the element to be defined (its bundle is
 * injected by {@link PluginRegistryProvider}), creates it once, and sets the host {@link PluginContext} on its
 * `ctx` property — reassigning it whenever the user / theme / locale / scope changes, which the SDK element
 * re-renders on. On unmount the element is removed.
 *
 * **Its failures are its own to catch.** It sits inside a {@link SlotRegion}'s error boundary, but a boundary
 * sees only React render errors, and this component's real work happens after render: the element is created
 * in a promise callback and renders in the custom-element lifecycle. A throw from creating it or from the
 * SDK's render on `ctx` assignment became an unhandled rejection — no tile, no `console.error`, and an
 * isolation promise that did not hold for the most likely failure (core#185). Those are caught here and shown
 * the same way the boundary would, and a bundle that never defines its element gives up after
 * {@link DEFINE_TIMEOUT_MS} instead of leaving an empty tile forever. What still escapes is a throw inside
 * the element's own `connectedCallback`, which the browser reports to `window` rather than to the caller.
 */
interface PluginMountProps {
  pluginId: string;
  tag: string;
  scope: Scope;
  episodes: string[];
  episodeLabels: Record<string, string>;
  /** Subpath below `/p/{pluginId}/` when this mount is a deep-link page (§6.4); empty elsewhere. */
  routePath?: string;
  /** Whether the plugin declares `storage.schema`; decides `ctx.schema` vs `null` (§7.6). */
  hasSchema?: boolean;
  hasBlobs?: boolean;
  hasTags?: boolean;
  /** Whether the plugin declares an `identity` block (§8.8). */
  hasIdentity?: boolean;
  /** Whether the plugin declares a `notifications` block (§17.1). */
  hasNotifications?: boolean;
  /**
   * Whether the host said this plugin gets a `ctx.translation` client — its manifest declared the kind *and*
   * a provider is configured (§16). The shell never reconstructs those two halves; it is told the answer.
   */
  hasTranslation?: boolean;
}

/** How long a plugin's bundle has to define its element before the tile is given up on. */
export const DEFINE_TIMEOUT_MS = 10_000;

export function PluginMount({
  pluginId,
  tag,
  scope,
  episodes,
  episodeLabels,
  routePath,
  hasSchema,
  hasBlobs,
  hasTags,
  hasIdentity,
  hasNotifications,
  hasTranslation,
}: PluginMountProps) {
  // Only a page mount is addressed by the URL, so only a page mount reads the query and hash off it. On a
  // card in a slot region that query belongs to the shell's own filters (`ctx.filter`), and subscribing to
  // it here would rebuild every mounted plugin's ctx on a navigation that had nothing to do with it.
  const location = useLocation();
  const routeQuery = routePath == null ? '' : location.search;
  const routeHash = routePath == null ? '' : location.hash;

  const hostRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<HTMLElement | null>(null);
  const [failed, setFailed] = useState(false);
  const { user } = useUser();
  const { site, mode } = useSite();
  // Every region builds its scope inline — `<SlotRegion scope={{ type: 'episode', id: slug }} />` — so the
  // object is a new one on every render of the component that hosts it, and a card's host re-renders
  // whenever anything above it does. Taking the object as a `ctx` input therefore reassigned `ctx` for a
  // scope that had not changed; measured at ~304 requests a second from one visitor with audio playing,
  // because the SDK re-renders the element on every assignment and each render re-runs its fetches. Depend
  // on the two values the scope actually is.
  const scopeType = scope.type;
  const scopeId = scope.id;
  const stableScope = useMemo<Scope>(() => ({ type: scopeType, id: scopeId }), [scopeType, scopeId]);

  // Actions only, never the player's state: `currentTime` moves about four times a second while audio
  // plays, and subscribing to it here would re-render this component — and rebuild `ctx` — at that rate.
  // That is the same hazard as the scope above and as `navigate` below, and it is why the actions context
  // exposes the position as a getter rather than as a value.
  const player = usePlayerActions();
  const playerRef = useRef(player);
  playerRef.current = player;
  const playerCurrentTime = useCallback(() => playerRef.current.getCurrentTime(), []);
  const playerSeekTo = useCallback((seconds: number) => playerRef.current.seek(seconds), []);
  const consent = useConsent();
  const { i18n } = useTranslation();

  // Held in a ref and wrapped: `useNavigate` is not guaranteed stable across renders, and it is a `ctx`
  // input — an unstable one would rebuild `ctx` and reassign it on every location change, re-rendering
  // every mounted plugin for a navigation that had nothing to do with it.
  const navigate = useNavigate();
  const navigateRef = useRef(navigate);
  navigateRef.current = navigate;
  const navigateTo = useCallback(
    (path: string, opts?: { replace?: boolean }) => navigateRef.current(path, { replace: opts?.replace }),
    [],
  );

  const ctx = useMemo(
    () =>
      buildCtx({
        pluginId,
        scope: stableScope,
        episodes,
        episodeLabels,
        user,
        theme: site?.theme[mode],
        locale: i18n.language,
        uiLocales: uiLocaleInfos(),
        contentLocales: contentLocaleInfos(),
        playerCurrentTime,
        playerSeekTo,
        routePath,
        routeQuery,
        routeHash,
        hasSchema,
        hasBlobs,
        hasTags,
        hasIdentity,
        hasNotifications,
        hasTranslation,
        navigateTo,
        consentHas: consent.has,
        consentGranted: consent.granted,
        consentRequest: consent.request,
        consentSubscribe: consent.subscribe,
      }),
    [
      pluginId,
      stableScope,
      episodes,
      episodeLabels,
      user,
      site,
      mode,
      i18n.language,
      playerCurrentTime,
      playerSeekTo,
      routePath,
      routeQuery,
      routeHash,
      hasSchema,
      hasBlobs,
      hasTags,
      hasIdentity,
      hasNotifications,
      hasTranslation,
      navigateTo,
      consent.has,
      consent.granted,
      consent.request,
      consent.subscribe,
    ],
  );

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const fail = (reason: unknown) => {
      if (!cancelled) {
        console.error(`Plugin '${pluginId}' could not render <${tag}>`, reason);
        setFailed(true);
      }
    };
    const defined = customElements.get(tag)
      ? Promise.resolve()
      : Promise.race([
          customElements.whenDefined(tag),
          new Promise<never>((_, reject) => {
            timer = setTimeout(
              () => reject(new Error(`<${tag}> was not defined within ${DEFINE_TIMEOUT_MS / 1000} s`)),
              DEFINE_TIMEOUT_MS,
            );
          }),
        ]);
    defined
      .then(() => {
        if (cancelled || !hostRef.current) {
          return;
        }
        let element = elementRef.current;
        if (!element) {
          element = document.createElement(tag);
          hostRef.current.appendChild(element);
          elementRef.current = element;
        }
        // The SDK element re-renders whenever ctx is (re)assigned — synchronously, so its throw lands here.
        (element as HTMLElement & { ctx?: unknown }).ctx = ctx;
      })
      .catch(fail)
      .finally(() => clearTimeout(timer));
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [tag, ctx, pluginId]);

  // Remove the element on unmount (the SDK runs the render's cleanup on disconnect).
  useEffect(
    () => () => {
      elementRef.current?.remove();
      elementRef.current = null;
    },
    [],
  );

  if (failed) {
    return <SlotFailed />;
  }
  return <div ref={hostRef} className="mc-plugin-mount" data-plugin={pluginId} />;
}
