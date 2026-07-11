// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api } from '../api/client';
import type { Mode, SiteView } from '../api/types';
import { applyTheme, resolveMode } from './applyTheme';

/**
 * Loads the public site payload once (`GET /api/site`), applies the generated theme, and exposes site
 * name / branding / theme to the shell (ARCHITECTURE §12.1/§12.3). The payload is cached in
 * `localStorage` under `mc.site` so the inline no-flash script in `index.html` can theme the very next
 * load before first paint. When the mode policy is `system`, the OS preference is followed live.
 */

export const SITE_CACHE_KEY = 'mc.site';

interface SiteContextValue {
  site: SiteView | null;
  mode: Mode;
}

const SiteContext = createContext<SiteContextValue>({ site: null, mode: 'light' });

export function useSite(): SiteContextValue {
  return useContext(SiteContext);
}

export function SiteProvider({ children }: { children: ReactNode }) {
  const [site, setSite] = useState<SiteView | null>(null);
  const [mode, setMode] = useState<Mode>(
    () => (document.documentElement.getAttribute('data-theme') as Mode | null) ?? 'light',
  );

  // Load the site payload, apply its theme, and cache it for the no-flash script.
  useEffect(() => {
    let active = true;
    api
      .get<SiteView>('/api/site')
      .then((payload) => {
        if (!active) {
          return;
        }
        setSite(payload);
        localStorage.setItem(SITE_CACHE_KEY, JSON.stringify(payload));
        const resolved = resolveMode(payload.modePolicy);
        applyTheme(payload.theme, resolved);
        setMode(resolved);
      })
      .catch(() => {
        /* site payload is best-effort; the CSS/no-flash fallback keeps the shell readable */
      });
    return () => {
      active = false;
    };
  }, []);

  // When the policy is `system`, re-apply as the OS light/dark preference changes.
  useEffect(() => {
    if (!site || site.modePolicy !== 'system') {
      return;
    }
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => {
      const resolved = resolveMode('system');
      applyTheme(site.theme, resolved);
      setMode(resolved);
    };
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, [site]);

  const value = useMemo(() => ({ site, mode }), [site, mode]);
  return <SiteContext.Provider value={value}>{children}</SiteContext.Provider>;
}
