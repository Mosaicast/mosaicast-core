// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { Component, useEffect, useMemo, useState, type ReactNode } from 'react';

import type { Scope } from '@mosaicast/plugin-sdk';

import { api } from '../api/client';
import { useUser } from '../auth/UserContext';
import { PluginMount } from '../plugins/PluginMount';
import { usePluginRegistry } from '../plugins/PluginRegistry';
import { selectMounts } from '../plugins/slots';

/**
 * A plugin slot region (ARCHITECTURE §7.3/§7.8). Renders a `data-slot` container and mounts every plugin
 * whose manifest declares a slot at this `placement` for the current `scope` level, gated by `visibleTo` and
 * stacked by `order` (ties broken by plugin id). Each mount is wrapped in an error boundary so a throwing
 * plugin blanks only its own tile — never the page (§7.8). Any host `children` render first.
 */

const SITE_SCOPE: Scope = { type: 'site', id: 'main' };

interface SlotRegionProps {
  name: 'top' | 'card' | 'main' | 'sidebar' | 'player' | 'feed' | 'site';
  /** The scope this region is rendered in; defaults to the site scope. */
  scope?: Scope;
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
      return <div className="mc-slot__error">plugin error</div>;
    }
    return this.props.children;
  }
}

export function SlotRegion({ name, scope = SITE_SCOPE, children }: SlotRegionProps) {
  const { plugins } = usePluginRegistry();
  const { user } = useUser();
  const role = user?.role;

  // Which plugin elements belong in this (placement, scope), visible to this user, in stack order.
  const mounts = useMemo(() => selectMounts(plugins, name, scope.type, role), [plugins, name, scope.type, role]);

  // Host-resolved episodes for the scope: slugs (ctx.episodes) + slug→label (ctx.episodeLabels). Fetched
  // only when something mounts here.
  const [episodes, setEpisodes] = useState<string[]>([]);
  const [episodeLabels, setEpisodeLabels] = useState<Record<string, string>>({});
  const hasMounts = mounts.length > 0;
  useEffect(() => {
    if (!hasMounts) {
      return;
    }
    let cancelled = false;
    api
      .get<{ id: string; label: string }[]>(
        `/api/plugins/scope-episodes?type=${scope.type}&id=${encodeURIComponent(scope.id)}`,
      )
      .then((options) => {
        if (!cancelled) {
          setEpisodes(options.map((o) => o.id));
          setEpisodeLabels(Object.fromEntries(options.map((o) => [o.id, o.label])));
        }
      })
      .catch(() => {
        setEpisodes([]);
        setEpisodeLabels({});
      });
    return () => {
      cancelled = true;
    };
  }, [hasMounts, scope.type, scope.id]);

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
          />
        </SlotErrorBoundary>
      ))}
    </div>
  );
}
