// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';

import type { Scope } from '@mosaicast/plugin-sdk';

import { useUser } from '../auth/UserContext';
import { useConsent } from '../consent/ConsentContext';
import { contentLocaleInfos, uiLocaleInfos } from '../i18n';
import { usePlayer } from '../player/PlayerContext';
import { useSite } from '../theme/SiteContext';
import { buildCtx } from './buildCtx';

/**
 * Mounts one plugin custom element (ARCHITECTURE §7.5). Waits for the element to be defined (its bundle is
 * injected by {@link PluginRegistryProvider}), creates it once, and sets the host {@link PluginContext} on its
 * `ctx` property — reassigning it whenever the user / theme / locale / scope changes, which the SDK element
 * re-renders on. On unmount the element is removed. Each mount sits inside a {@link SlotRegion}'s error
 * boundary, so a throwing element blanks only its own tile.
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
}

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
}: PluginMountProps) {
  // Only a page mount is addressed by the URL, so only a page mount reads the query and hash off it. On a
  // card in a slot region that query belongs to the shell's own filters (`ctx.filter`), and subscribing to
  // it here would rebuild every mounted plugin's ctx on a navigation that had nothing to do with it.
  const location = useLocation();
  const routeQuery = routePath == null ? '' : location.search;
  const routeHash = routePath == null ? '' : location.hash;

  const hostRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<HTMLElement | null>(null);
  const { user } = useUser();
  const { site, mode } = useSite();
  const player = usePlayer();
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
        scope,
        episodes,
        episodeLabels,
        user,
        theme: site?.theme[mode],
        locale: i18n.language,
        uiLocales: uiLocaleInfos(),
        contentLocales: contentLocaleInfos(),
        playerCurrentTime: () => player.currentTime,
        playerSeekTo: player.seek,
        routePath,
        routeQuery,
        routeHash,
        hasSchema,
        hasBlobs,
        hasTags,
        navigateTo,
        consentHas: consent.has,
        consentGranted: consent.granted,
        consentRequest: consent.request,
        consentSubscribe: consent.subscribe,
      }),
    [
      pluginId,
      scope,
      episodes,
      episodeLabels,
      user,
      site,
      mode,
      i18n.language,
      player,
      routePath,
      routeQuery,
      routeHash,
      hasSchema,
      hasBlobs,
      hasTags,
      navigateTo,
      consent.has,
      consent.granted,
      consent.request,
      consent.subscribe,
    ],
  );

  useEffect(() => {
    let cancelled = false;
    void customElements.whenDefined(tag).then(() => {
      if (cancelled || !hostRef.current) {
        return;
      }
      let element = elementRef.current;
      if (!element) {
        element = document.createElement(tag);
        hostRef.current.appendChild(element);
        elementRef.current = element;
      }
      // The SDK element re-renders whenever ctx is (re)assigned.
      (element as HTMLElement & { ctx?: unknown }).ctx = ctx;
    });
    return () => {
      cancelled = true;
    };
  }, [tag, ctx]);

  // Remove the element on unmount (the SDK runs the render's cleanup on disconnect).
  useEffect(
    () => () => {
      elementRef.current?.remove();
      elementRef.current = null;
    },
    [],
  );

  return <div ref={hostRef} className="mc-plugin-mount" data-plugin={pluginId} />;
}
