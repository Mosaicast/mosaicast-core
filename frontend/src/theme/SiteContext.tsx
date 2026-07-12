// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api } from '../api/client';
import type { Mode, SiteView } from '../api/types';
import i18n, { availableLocales } from '../i18n';
import { applyTheme, resolveMode } from './applyTheme';

/**
 * Resolution order for the UI language (ARCHITECTURE §12.7): an explicit stored choice wins; otherwise, when
 * the browser language isn't one we ship, adopt the site default. i18n was already initialised with
 * stored/browser/en before the payload arrived; this only fills the "no choice, unknown browser" case.
 */
function applyDefaultLocale(defaultLocale: string): void {
  if (localStorage.getItem('mc.locale')) {
    return; // an explicit choice always wins
  }
  const supported = availableLocales();
  const browser = (typeof navigator !== 'undefined' ? navigator.language : 'en').slice(0, 2);
  if (!supported.includes(browser) && supported.includes(defaultLocale)) {
    void i18n.changeLanguage(defaultLocale);
  }
}

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
  /** Re-fetch `/api/site` and re-apply the theme — call after an admin edit so the shell reflects it live. */
  refresh: () => Promise<void>;
}

const SiteContext = createContext<SiteContextValue>({ site: null, mode: 'light', refresh: async () => {} });

export function useSite(): SiteContextValue {
  return useContext(SiteContext);
}

export function SiteProvider({ children }: { children: ReactNode }) {
  const [site, setSite] = useState<SiteView | null>(null);
  const [mode, setMode] = useState<Mode>(
    () => (document.documentElement.getAttribute('data-theme') as Mode | null) ?? 'light',
  );

  // Load the site payload, apply its theme, and cache it for the no-flash script.
  const refresh = useCallback(async () => {
    try {
      const payload = await api.get<SiteView>('/api/site');
      setSite(payload);
      localStorage.setItem(SITE_CACHE_KEY, JSON.stringify(payload));
      applyDefaultLocale(payload.defaultLocale);
      const resolved = resolveMode(payload.modePolicy);
      applyTheme(payload.theme, resolved);
      setMode(resolved);
    } catch {
      /* site payload is best-effort; the CSS/no-flash fallback keeps the shell readable */
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

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

  const value = useMemo(() => ({ site, mode, refresh }), [site, mode, refresh]);
  return <SiteContext.Provider value={value}>{children}</SiteContext.Provider>;
}
