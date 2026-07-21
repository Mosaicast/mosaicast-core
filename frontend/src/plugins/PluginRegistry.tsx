// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

import { api } from '../api/client';
import type { PublicPlugin } from './types';

/**
 * Loads the plugin manifest once (`GET /api/plugins/manifest`) and injects each plugin's frontend bundle a
 * single time (ARCHITECTURE §7.5). Importing a bundle registers its Web Components via the SDK's
 * `defineMosaicastElement`; the shell then mounts those elements into slot regions ({@link SlotRegion}).
 *
 * <p>Loading is best-effort and isolated: a bundle that fails to import is logged and skipped — the shell and
 * the other plugins are unaffected (§7.8). The bundles are same-origin (`/plugins/{id}/assets/…`), so the
 * strict `script-src 'self'` CSP allows them.
 */
interface RegistryValue {
  plugins: PublicPlugin[];
}

const PluginRegistryContext = createContext<RegistryValue>({ plugins: [] });

export function usePluginRegistry(): RegistryValue {
  return useContext(PluginRegistryContext);
}

const injected = new Set<string>();

function injectBundle(plugin: PublicPlugin): void {
  if (!plugin.frontend?.entry || injected.has(plugin.id)) {
    return;
  }
  injected.add(plugin.id);
  const url = `/plugins/${plugin.id}/assets/${plugin.frontend.entry}`;
  // Dynamic same-origin import; its side effect is registering the plugin's custom elements.
  import(/* @vite-ignore */ url).catch((err) => {
    // Isolated: a broken bundle disables only that plugin's UI, never the shell.
    console.error(`Failed to load plugin bundle '${plugin.id}' from ${url}`, err);
  });
}

export function PluginRegistryProvider({ children }: { children: ReactNode }) {
  const [plugins, setPlugins] = useState<PublicPlugin[]>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .get<PublicPlugin[]>('/api/plugins/manifest')
      .then((list) => {
        if (cancelled) {
          return;
        }
        setPlugins(list);
        list.forEach(injectBundle);
      })
      .catch(() => {
        /* no plugins / offline — the zero-plugin shell works unchanged */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <PluginRegistryContext.Provider value={{ plugins }}>{children}</PluginRegistryContext.Provider>
  );
}
