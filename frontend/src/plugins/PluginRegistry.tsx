// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api } from '../api/client';
import { useMeta } from '../api/MetaContext';
import { useConsent } from '../consent/ConsentContext';
import { installStorageAudit } from './storageAudit';
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
  /**
   * Whether `plugins` is an answer yet. An empty list meant both "no plugins" and "not asked yet", so a deep
   * link to `/p/<id>` rendered the 404 while the manifest was still in flight — and permanently when that
   * request failed, since the failure was swallowed (core#185). Slot regions need none of this: rendering
   * nothing until the answer arrives is right for them. A page has to tell the three apart.
   */
  status: 'loading' | 'ready' | 'failed';
  /** Asks again after a failure. */
  reload: () => void;
}

const PluginRegistryContext = createContext<RegistryValue>({
  plugins: [],
  status: 'ready',
  reload: () => {},
});

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
  const [status, setStatus] = useState<RegistryValue['status']>('loading');
  const [attempt, setAttempt] = useState(0);
  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const devProfile = useMeta()?.devLoginEnabled ?? false;
  const consent = useConsent();

  // Dev only, and before any bundle is imported: a plugin that writes storage it never declared makes the
  // privacy settings wrong, and that is worth catching while developing it. Detection, not containment —
  // see `storageAudit`.
  useEffect(() => {
    if (devProfile && consent.fingerprint) {
      installStorageAudit(consent);
    }
  }, [devProfile, consent]);

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    api
      .get<PublicPlugin[]>('/api/plugins/manifest')
      .then((list) => {
        if (cancelled) {
          return;
        }
        setPlugins(list);
        setStatus('ready');
        list.forEach(injectBundle);
      })
      .catch((err) => {
        // The zero-plugin shell still works; what must not happen is a plugin page claiming not to exist.
        if (!cancelled) {
          console.error('Failed to load the plugin manifest', err);
          setStatus('failed');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const value = useMemo(() => ({ plugins, status, reload }), [plugins, status, reload]);
  return <PluginRegistryContext.Provider value={value}>{children}</PluginRegistryContext.Provider>;
}
