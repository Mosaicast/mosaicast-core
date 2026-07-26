// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

import { api } from '../api/client';

/**
 * The shell's side of the consent service (ARCHITECTURE §12.5). The core sets only strictly necessary and
 * functional storage, so it stays **banner-free**: this asks for nothing until an active plugin declares a
 * category. What a plugin declared is what visitors are asked about — and the same declaration is what the
 * server's CSP allows, so an undeclared third party cannot load even with consent given.
 *
 * Decisions live in `localStorage` (like `mc.locale`) so they work for anonymous visitors, which is exactly
 * who a banner is for. Granting is per category and always revocable.
 */

const STORAGE_KEY = 'mc.consent';

interface ConsentCategory {
  category: string;
  pluginIds: string[];
  known: boolean;
}

interface ConsentSource {
  source: string;
  pluginId: string;
}

interface ConsentPayload {
  categories: ConsentCategory[];
  sources: ConsentSource[];
  privacySlug: string | null;
}

interface ConsentValue extends ConsentPayload {
  /** Whether a category was granted; `necessary` is implicit and always true. */
  has: (category: string) => boolean;
  /** Records a decision per category and closes the banner. */
  decide: (decisions: Record<string, boolean>) => void;
  /** True once a decision was stored — until then the banner asks. */
  decided: boolean;
  /** Reopens the settings after a decision (the footer entry point). */
  reopen: () => void;
  settingsOpen: boolean;
}

const EMPTY: ConsentPayload = { categories: [], sources: [], privacySlug: null };

const ConsentContext = createContext<ConsentValue>({
  ...EMPTY,
  has: () => false,
  decide: () => {},
  decided: true,
  reopen: () => {},
  settingsOpen: false,
});

/**
 * Consent sits at the root of the shell, so a payload that is absent, partial or from an older/newer server
 * must degrade to "nothing to ask" — never to a crash that takes the whole page down with it.
 */
function normalize(payload: Partial<ConsentPayload> | null | undefined): ConsentPayload {
  return {
    categories: Array.isArray(payload?.categories)
      ? payload.categories.filter((c) => typeof c?.category === 'string')
      : [],
    sources: Array.isArray(payload?.sources) ? payload.sources : [],
    privacySlug: typeof payload?.privacySlug === 'string' ? payload.privacySlug : null,
  };
}

function readStored(): Record<string, boolean> | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Record<string, boolean>) : null;
  } catch {
    return null;
  }
}

export function ConsentProvider({ children }: { children: ReactNode }) {
  const [payload, setPayload] = useState<ConsentPayload>(EMPTY);
  const [decisions, setDecisions] = useState<Record<string, boolean> | null>(() => readStored());
  const [settingsOpen, setSettingsOpen] = useState(false);

  useEffect(() => {
    api
      .get<Partial<ConsentPayload>>('/api/consent')
      .then((payload) => setPayload(normalize(payload)))
      .catch(() => setPayload(EMPTY));
  }, []);

  const has = useCallback(
    (category: string) => {
      if (category === 'necessary') {
        return true;
      }
      // Default deny: an ungranted category is not consented to, whatever the plugin assumes.
      return decisions?.[category] === true;
    },
    [decisions],
  );

  const decide = useCallback((next: Record<string, boolean>) => {
    setDecisions(next);
    setSettingsOpen(false);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // A visitor blocking storage still gets their choice for this page view.
    }
  }, []);

  const value = useMemo<ConsentValue>(
    () => ({
      ...payload,
      has,
      decide,
      decided: decisions != null,
      reopen: () => setSettingsOpen(true),
      settingsOpen,
    }),
    [payload, has, decide, decisions, settingsOpen],
  );

  return <ConsentContext.Provider value={value}>{children}</ConsentContext.Provider>;
}

export function useConsent() {
  return useContext(ConsentContext);
}
