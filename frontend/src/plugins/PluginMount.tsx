// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import type { Scope } from '@mosaicast/plugin-sdk';

import { useUser } from '../auth/UserContext';
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
}

export function PluginMount({
  pluginId,
  tag,
  scope,
  episodes,
  episodeLabels,
  routePath,
}: PluginMountProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const elementRef = useRef<HTMLElement | null>(null);
  const { user } = useUser();
  const { site, mode } = useSite();
  const player = usePlayer();
  const { i18n } = useTranslation();

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
        playerCurrentTime: () => player.currentTime,
        playerSeekTo: player.seek,
        routePath,
      }),
    [pluginId, scope, episodes, episodeLabels, user, site, mode, i18n.language, player, routePath],
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
