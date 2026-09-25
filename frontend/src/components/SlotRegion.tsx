// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Component, useEffect, useMemo, useState, type ReactNode } from 'react';

import type { Scope } from '@mosaicast/plugin-sdk';

import { api } from '../api/client';
import { useUser } from '../auth/UserContext';
import { PluginMount } from '../plugins/PluginMount';
import { usePluginRegistry } from '../plugins/PluginRegistry';
import { selectMounts } from '../plugins/slots';
import { SlotFailed } from './SlotFailed';

/**
 * A plugin slot region (ARCHITECTURE §7.3/§7.8). Renders a `data-slot` container and mounts every plugin
 * whose manifest declares a slot at this `placement` for the current `scope` level, gated by `visibleTo` and
 * stacked by `order` (ties broken by plugin id). Each mount is wrapped in an error boundary so a throwing
 * plugin blanks only its own tile — never the page (§7.8). Any host `children` render first.
 */

const SITE_SCOPE: Scope = { type: 'site', id: 'main' };

/** One shared empty answer, so "nothing resolved" is the same identity on every render. */
const EMPTY_SCOPE: { ids: string[]; labels: Record<string, string> } = { ids: [], labels: {} };

interface SlotRegionProps {
  name: 'top' | 'card' | 'main' | 'sidebar' | 'player' | 'feed' | 'site' | 'admin' | 'page';
  /** The scope this region is rendered in; defaults to the site scope. */
  scope?: Scope;
  /**
   * The label of an `episode` scope, when the caller already has it. An episode card knows the title of the
   * episode it is drawing, and asking the host to resolve a one-element list it is holding is a request per
   * card: 21 of them on one measured 20-card page (core#159). Given this, the region skips the round trip
   * entirely. Ignored for any other scope type, where the host really does have to resolve the set.
   */
  scopeLabel?: string;
  /** Only for the `page` region: the subpath below `/p/{pluginId}/`, handed to plugins as `ctx.route`. */
  routePath?: string;
  /** Renders only the named plugin's slots — the deep-link page belongs to one plugin. */
  onlyPluginId?: string;
  children?: ReactNode;
}

interface BoundaryState {
  failed: boolean;
}

class SlotErrorBoundary extends Component<{ children: ReactNode }, BoundaryState> {
  state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  render() {
    if (this.state.failed) {
      // Isolated failure: a small note in place of this tile, nothing else disturbed.
      return <SlotFailed />;
    }
    return this.props.children;
  }
}

export function SlotRegion({
  name,
  scope = SITE_SCOPE,
  scopeLabel,
  routePath,
  onlyPluginId,
  children,
}: SlotRegionProps) {
  const { plugins } = usePluginRegistry();
  const { user } = useUser();
  const role = user?.role;

  // Which plugin elements belong in this (placement, scope), visible to this user, in stack order.
  const mounts = useMemo(
    () =>
      selectMounts(plugins, name, scope.type, role).filter(
        (mount) => onlyPluginId == null || mount.pluginId === onlyPluginId,
      ),
    [plugins, name, scope.type, role, onlyPluginId],
  );

  // Host-resolved episodes for the scope: slugs (ctx.episodes) + slug→label (ctx.episodeLabels). Fetched
  // only when something mounts here — and not at all when the caller handed us the one answer there is.
  const known = scope.type === 'episode' && scopeLabel != null;
  const [fetched, setFetched] = useState<{ ids: string[]; labels: Record<string, string> }>(EMPTY_SCOPE);
  const hasMounts = mounts.length > 0;
  useEffect(() => {
    // A new scope starts empty rather than showing the last one's episodes until its own answer arrives —
    // and a region that stops needing the answer drops it, so nothing stale is handed over if it needs it
    // again for a different scope (core#185). The same identity when already empty, so this costs nothing.
    setFetched(EMPTY_SCOPE);
    if (!hasMounts || known) {
      return;
    }
    let cancelled = false;
    const controller = new AbortController();
    api
      .get<{ id: string; label: string }[]>(
        `/api/plugins/scope-episodes?type=${scope.type}&id=${encodeURIComponent(scope.id)}`,
        { signal: controller.signal },
      )
      .then((options) => {
        if (!cancelled) {
          setFetched({
            ids: options.map((o) => o.id),
            labels: Object.fromEntries(options.map((o) => [o.id, o.label])),
          });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFetched(EMPTY_SCOPE);
        }
      });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [hasMounts, known, scope.type, scope.id]);

  // Memoised so a re-render of this region does not hand every mount a new array and a new object — those
  // are `ctx` inputs, and a fresh identity there costs the same as a changed scope did (core#158).
  const episodes = useMemo(
    () => (known ? [scope.id] : fetched.ids),
    [known, scope.id, fetched.ids],
  );
  const episodeLabels = useMemo(
    () => (known ? { [scope.id]: scopeLabel } : fetched.labels),
    [known, scope.id, scopeLabel, fetched.labels],
  );

  return (
    <div className="mc-slot" data-slot={name}>
      <SlotErrorBoundary>{children ?? null}</SlotErrorBoundary>
      {mounts.map((mount) => (
        <SlotErrorBoundary key={mount.key}>
          <PluginMount
            pluginId={mount.pluginId}
            tag={mount.element}
            scope={scope}
            episodes={episodes}
            episodeLabels={episodeLabels}
            routePath={routePath}
            hasSchema={mount.hasSchema}
            hasBlobs={mount.hasBlobs}
            hasTags={mount.hasTags}
            hasIdentity={mount.hasIdentity}
            hasNotifications={mount.hasNotifications}
            hasTranslation={mount.hasTranslation}
          />
        </SlotErrorBoundary>
      ))}
    </div>
  );
}
