// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

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
  /** Every currently granted category — the SDK's `ctx.consent.granted()`. */
  granted: () => string[];
  /**
   * Opens the settings for one category and resolves with what the visitor decided — the click-to-load flow
   * (§12.5). Resolves `false` if they decide without granting it.
   */
  request: (category: string) => Promise<boolean>;
  /**
   * Subscribes to decision changes; the returned function unsubscribes. Plugins depend on this: consent can
   * be withdrawn mid-session from the settings, and only a notification tells a mounted component to go back
   * to its placeholder.
   */
  subscribe: (listener: () => void) => () => void;
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
  granted: () => [],
  request: () => Promise.resolve(false),
  subscribe: () => () => {},
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
  /** Subscribers (plugins) and in-flight `request()` calls awaiting the visitor's next decision. */
  const listeners = useRef(new Set<() => void>());
  const pending = useRef<{ category: string; resolve: (granted: boolean) => void }[]>([]);

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
    // Tell anything already mounted — a plugin that loaded third-party content must be able to unload it
    // the moment the visitor withdraws, not at the next navigation.
    listeners.current.forEach((listener) => listener());
    pending.current.splice(0).forEach(({ category, resolve }) => resolve(next[category] === true));
  }, []);

  const granted = useCallback(
    () => Object.entries(decisions ?? {}).filter(([, on]) => on).map(([category]) => category),
    [decisions],
  );

  const subscribe = useCallback((listener: () => void) => {
    listeners.current.add(listener);
    return () => listeners.current.delete(listener);
  }, []);

  /**
   * Opens the settings and resolves once the visitor decides. Deliberately does not grant anything by
   * itself: a plugin asking is not a plugin receiving, and an unprompted call would turn a banner-free site
   * into one with a banner (§12.5) — which is why the SDK tells plugins to call this from a click.
   */
  const request = useCallback((category: string) => {
    setSettingsOpen(true);
    return new Promise<boolean>((resolve) => pending.current.push({ category, resolve }));
  }, []);

  const value = useMemo<ConsentValue>(
    () => ({
      ...payload,
      has,
      granted,
      request,
      subscribe,
      decide,
      decided: decisions != null,
      reopen: () => setSettingsOpen(true),
      settingsOpen,
    }),
    [payload, has, granted, request, subscribe, decide, decisions, settingsOpen],
  );

  return <ConsentContext.Provider value={value}>{children}</ConsentContext.Provider>;
}

export function useConsent() {
  return useContext(ConsentContext);
}
