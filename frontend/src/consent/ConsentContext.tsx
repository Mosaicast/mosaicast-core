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
import { useMeta } from '../api/MetaContext';
import type { ConsentPayload } from '../api/types';
import { CONSENT_COOKIE, PROGRESS_PREF_KEY, STORAGE_KEY, purgeUndeclared } from './purge';

/**
 * The shell's side of the consent service (ARCHITECTURE §12.5).
 *
 * The core stores only what the requested service needs, so it stays **banner-free**: nothing is asked until
 * an active plugin declares a third-party service. What that plugin declared is what visitors are asked
 * about — and the same declaration is what the server's CSP allows, so an undeclared third party cannot load
 * even with consent given.
 *
 * Decisions live in `localStorage`, like `mc.locale`, because a consent surface that needs a login is not a
 * consent surface. Three things make a stored decision stop counting, and each is a rule rather than a
 * nicety:
 *
 * - **The declaration changed.** The stored record carries the `fingerprint` the server computed over
 *   everything a visitor is told. Install a plugin and the fingerprint moves, so consent given for two
 *   services cannot silently cover a third. Before this, a stored decision was simply a decision, whatever
 *   it had been about.
 * - **It expired.** Twelve months, dated by `decidedAt` rather than inferred.
 * - **It was never asked for.** Global Privacy Control is a machine-readable objection; where the browser
 *   sends one, everything optional counts as refused and no banner goes in front of someone who has already
 *   answered. An explicit choice in the settings still wins — the signal is a default, not a cage.
 */

const RECORD_VERSION = 1;
const TWELVE_MONTHS_MS = 365 * 24 * 60 * 60 * 1000;

/**
 * The decision, mirrored into a cookie so the **server** can act on it (`ConsentCookie` on the Java side).
 *
 * `localStorage` is invisible to an HTTP response, so the Content-Security-Policy — the one thing a plugin
 * cannot talk its way past — had to be written blind and therefore allowed every declared origin whatever
 * the visitor chose. With the decision in a cookie the server narrows the policy to the categories actually
 * granted, so a plugin that ignores `ctx.consent.has()` gets a blocked request instead of a silent one.
 *
 * Dot-separated because `Set-Cookie` treats commas and spaces as separators. Strictly necessary — it exists
 * only to carry out the visitor's own refusal — and disclosed with everything else.
 *
 * Its name, and the two `localStorage` keys beside it, are defined in `./purge` rather than here: the sweep
 * has to recognise them before any payload arrives, so that it can never delete the decision it enforces.
 */
const COOKIE_MAX_AGE_SECONDS = 400 * 24 * 60 * 60;

function writeConsentCookie(categories: Record<string, boolean>): void {
  const granted = Object.entries(categories)
    .filter(([, on]) => on)
    .map(([category]) => category)
    .filter((category) => /^[a-z0-9_-]{1,40}$/i.test(category))
    .join('.');
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie =
    `${CONSENT_COOKIE}=${granted}; Path=/; Max-Age=${COOKIE_MAX_AGE_SECONDS}; SameSite=Lax${secure}`;
}

/** Drops the mirror, so the server falls back to allowing nothing optional. */
/**
 * A comparable summary of the decision *as the server's CSP will read it*.
 *
 * <p>Two answers that produce the same policy are the same answer for reload purposes, however differently
 * the visitor arrived at them. Only categories some service declared an origin for can move the policy
 * (`affectsPolicy`), so toggling a purely storage-based category — or re-saving an unchanged form — changes
 * this string not at all and costs nobody their place in an episode.
 */
function cspRelevantGrants(
  categories: Record<string, boolean>,
  payload: ConsentPayload,
): string {
  return (payload.categories ?? [])
    .filter((category) => category.affectsPolicy && categories[category.id] === true)
    .map((category) => category.id)
    .sort()
    .join('.');
}

function clearConsentCookie(): void {
  document.cookie = `${CONSENT_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax`;
}

/**
 * The stored decision — a **consent receipt**, kept on the visitor's own device.
 *
 * Deliberately not sent to the server: a per-visitor consent table would be a new store of personal data,
 * needing its own legal basis, retention and deletion, in order to prove something about visitors who are
 * otherwise anonymous here. The visitor can read and export this from the settings page instead, and the
 * operator's proof is what the site declared (`GET /api/admin/consent`) — the half they actually control.
 */
export interface ConsentRecord {
  version: number;
  /** ISO timestamp of the decision — what the twelve-month expiry is measured from. */
  decidedAt: string;
  /** The declaration this answer was given about. */
  fingerprint: string;
  /** Per category: granted or refused. Absent means refused. */
  categories: Record<string, boolean>;
}

interface ConsentValue extends ConsentPayload {
  /** Whether a category was granted; `necessary` is implicit and always true. */
  has: (category: string) => boolean;
  /** Every currently granted category — the SDK's `ctx.consent.granted()`. */
  granted: () => string[];
  /**
   * Opens the settings for one category and resolves with what the visitor decided — the click-to-load flow
   * (§12.5). Resolves `false` if they decide without granting it. Concurrent calls join the one surface and
   * each resolves exactly once, which is what the SDK promises plugin authors.
   */
  request: (category: string) => Promise<boolean>;
  /**
   * Subscribes to decision changes; the returned function unsubscribes. Plugins depend on this: consent can
   * be withdrawn mid-session from the settings, and only a notification tells a mounted component to go back
   * to its placeholder.
   */
  subscribe: (listener: () => void) => () => void;
  /** Records a decision per category. Everything not named is refused. */
  decide: (decisions: Record<string, boolean>) => void;
  /** Refuses everything optional — the withdrawal that has to be as easy as granting. */
  withdraw: () => void;
  /** True once a decision applies: stored, unexpired, and about the current declaration. */
  decided: boolean;
  /** The stored receipt, for the settings page to show and export. */
  record: ConsentRecord | null;
  /** True when the browser sent a Global Privacy Control signal and no explicit choice overrides it. */
  gpc: boolean;
  /** Opens the settings (the footer entry point and the `/cookies` page both use this). */
  openSettings: () => void;
  closeSettings: () => void;
  settingsOpen: boolean;
  /** Whether playback positions are being stored — an off switch, not a consent gate (see below). */
  progressEnabled: boolean;
  setProgressEnabled: (on: boolean) => void;
}

const EMPTY: ConsentPayload = {
  fingerprint: '',
  categories: [],
  essential: { storage: [] },
  necessaryServices: [],
  privacySlug: null,
};

const ConsentContext = createContext<ConsentValue>({
  ...EMPTY,
  has: () => false,
  granted: () => [],
  request: () => Promise.resolve(false),
  subscribe: () => () => {},
  decide: () => {},
  withdraw: () => {},
  decided: true,
  record: null,
  gpc: false,
  openSettings: () => {},
  closeSettings: () => {},
  settingsOpen: false,
  progressEnabled: true,
  setProgressEnabled: () => {},
});

/**
 * Consent sits at the root of the shell, so a payload that is absent, partial or from an older/newer server
 * must degrade to "nothing to ask" — never to a crash that takes the whole page down with it.
 */
function normalize(payload: Partial<ConsentPayload> | null | undefined): ConsentPayload {
  return {
    fingerprint: typeof payload?.fingerprint === 'string' ? payload.fingerprint : '',
    categories: Array.isArray(payload?.categories)
      ? payload.categories.filter((category) => typeof category?.id === 'string')
      : [],
    essential: { storage: payload?.essential?.storage ?? [] },
    necessaryServices: Array.isArray(payload?.necessaryServices) ? payload.necessaryServices : [],
    privacySlug: typeof payload?.privacySlug === 'string' ? payload.privacySlug : null,
  };
}

function readRecord(): ConsentRecord | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<ConsentRecord>;
    if (parsed?.version !== RECORD_VERSION || typeof parsed.decidedAt !== 'string') {
      return null;
    }
    return {
      version: RECORD_VERSION,
      decidedAt: parsed.decidedAt,
      fingerprint: typeof parsed.fingerprint === 'string' ? parsed.fingerprint : '',
      categories: parsed.categories ?? {},
    };
  } catch {
    return null;
  }
}

/** Whether a stored answer still answers the question being asked. */
export function isCurrent(record: ConsentRecord | null, fingerprint: string, now = Date.now()): boolean {
  if (!record || record.fingerprint !== fingerprint) {
    return false;
  }
  const decidedAt = Date.parse(record.decidedAt);
  return Number.isFinite(decidedAt) && now - decidedAt < TWELVE_MONTHS_MS;
}

/** The browser's Global Privacy Control signal, where it exists. */
function readGpc(): boolean {
  return (navigator as Navigator & { globalPrivacyControl?: boolean }).globalPrivacyControl === true;
}

/**
 * Whether the player may store listening positions.
 *
 * Not a consent category on purpose: it is first-party, stays on the device, is never profiled, and is only
 * ever written after the visitor deliberately pressed play — so it is part of the service they asked for, and
 * gating it behind a banner would trade a real feature for a fake choice. It is disclosed, and it has an off
 * switch, which is the honest arrangement.
 *
 * **Except when the browser has already objected.** On by default is defensible for a visitor who said
 * nothing; it is not defensible for one whose browser is asking sites not to track them. The strictly-
 * necessary exemption in §25 TDDDG / Art. 5(3) ePD covers a media player's *session* state comfortably, and
 * a position that persists indefinitely sits at the edge of it (WP29 Opinion 04/2012) — so where GPC is
 * present, the edge case defaults off. It is still a switch, not a lock: turning it on stores an explicit
 * `on`, which outranks the signal from then on.
 *
 * Exported as a plain function so the player can read it without reaching for React context in a callback.
 */
export function progressEnabled(): boolean {
  try {
    const preference = localStorage.getItem(PROGRESS_PREF_KEY);
    if (preference === 'on' || preference === 'off') {
      return preference === 'on';
    }
    return !readGpc();
  } catch {
    return true;
  }
}

export function ConsentProvider({ children }: { children: ReactNode }) {
  const [payload, setPayload] = useState<ConsentPayload>(EMPTY);
  const [record, setRecord] = useState<ConsentRecord | null>(() => readRecord());
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [progressOn, setProgressOn] = useState(() => progressEnabled());
  const gpc = useMemo(readGpc, []);
  const devProfile = useMeta()?.devLoginEnabled ?? false;
  /** Subscribers (plugins) and in-flight `request()` calls awaiting the visitor's next decision. */
  const listeners = useRef(new Set<() => void>());
  const pending = useRef<{ category: string; resolve: (granted: boolean) => void }[]>([]);

  useEffect(() => {
    api
      .get<Partial<ConsentPayload>>('/api/consent')
      .then((fetched) => setPayload(normalize(fetched)))
      .catch(() => setPayload(EMPTY));
  }, []);

  // Keep the server's copy honest about a decision that stopped counting. An expired answer, or one given
  // about a different set of services, must not leave a stale allow-list in the policy — and a cookie the
  // visitor cleared while keeping localStorage (or the reverse) has to converge on the stricter of the two.
  useEffect(() => {
    if (!payload.fingerprint) {
      return;
    }
    if (isCurrent(record, payload.fingerprint)) {
      writeConsentCookie(record!.categories);
    } else {
      clearConsentCookie();
    }
  }, [payload.fingerprint, record]);

  // Enforce the decision on what is already *on* the device, not only on what may be fetched next.
  //
  // Withdrawal has to mean the data goes, and a plugin uninstalled while the visitor was away leaves keys no
  // future decision of theirs will ever touch — so this runs on load as well as after every answer, keyed on
  // the declaration and the answer together. `purgeUndeclared` no-ops until a fingerprint has arrived.
  useEffect(() => {
    const removed = purgeUndeclared(payload, (category) =>
      isCurrent(record, payload.fingerprint) ? record!.categories[category] === true : false,
    );
    const total = removed.localStorage.length + removed.sessionStorage.length + removed.cookies.length;
    // Only where a developer is watching — the same signal `storageAudit` uses. A plugin author whose storage
    // keeps vanishing deserves to be told why and told the fix; a visitor does not need the console noise.
    if (total > 0 && devProfile) {
      console.warn(
        `[mosaicast] purged ${total} undeclared or withdrawn item(s):`,
        removed,
        '\nDeclared storage survives a sweep — add it to consent.services[].storage in the manifest.',
      );
    }
  }, [payload, record, devProfile]);

  const stored = isCurrent(record, payload.fingerprint);
  const effective = useMemo(
    () => (stored && record ? record.categories : {}),
    [stored, record],
  );

  const has = useCallback(
    (category: string) => {
      if (category === 'necessary') {
        return true;
      }
      // Default deny: an ungranted category is not consented to, whatever the plugin assumes.
      return effective[category] === true;
    },
    [effective],
  );

  const granted = useCallback(
    () => Object.entries(effective).filter(([, on]) => on).map(([category]) => category),
    [effective],
  );

  const decide = useCallback(
    (next: Record<string, boolean>) => {
      const before = cspRelevantGrants(effective, payload);
      const receipt: ConsentRecord = {
        version: RECORD_VERSION,
        decidedAt: new Date().toISOString(),
        fingerprint: payload.fingerprint,
        categories: next,
      };
      setRecord(receipt);
      setSettingsOpen(false);
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(receipt));
      } catch {
        // A visitor blocking storage still gets their choice for this page view.
      }
      // The server reads this on the next request and narrows the CSP accordingly. Written even when
      // localStorage refused, since the cookie is what makes the refusal enforceable.
      writeConsentCookie(next);
      // Tell anything already mounted — a plugin that loaded third-party content must be able to unload it
      // the moment the visitor withdraws, not at the next navigation.
      listeners.current.forEach((listener) => listener());
      pending.current.splice(0).forEach(({ category, resolve }) => resolve(next[category] === true));

      // Then make the decision true of *this* page, not only the next one.
      //
      // Enforcement lives in the response CSP, which the server built from the cookie as it was when this
      // document was requested. A document's CSP cannot be changed after delivery — no meta tag, no header
      // rewrite, nothing. So clicking "Accept all" set the cookie, fired the listeners, and the plugin's
      // script was still blocked against the narrow policy already in force; clicking "Reject all" left the
      // wide policy in place for the rest of the session, contradicting what `ConsentCookie` documents it
      // guarantees. Routing is client-side, so without this the contradiction survives every navigation
      // until a hard reload the visitor has no reason to perform.
      //
      // Only when the policy actually changes. A visitor who reopens the settings and saves the same answer,
      // or toggles a category no service declares hosts for, should not lose their place in an episode.
      if (cspRelevantGrants(next, payload) !== before) {
        window.location.reload();
      }
    },
    [effective, payload],
  );

  const withdraw = useCallback(() => {
    decide(Object.fromEntries(payload.categories.map((category) => [category.id, false])));
  }, [decide, payload.categories]);

  /**
   * Closing the surface without deciding is itself an answer, and the SDK promises that **every**
   * `request()` resolves exactly once and always. Resolving with the category's current state means a
   * dismissal reads as "not granted" without silently recording a refusal the visitor never made.
   */
  const closeSettings = useCallback(() => {
    setSettingsOpen(false);
    pending.current.splice(0).forEach(({ category, resolve }) => resolve(has(category)));
  }, [has]);

  const subscribe = useCallback((listener: () => void) => {
    listeners.current.add(listener);
    return () => {
      listeners.current.delete(listener);
    };
  }, []);

  /**
   * Opens the settings and resolves once the visitor decides. Deliberately does not grant anything by
   * itself: a plugin asking is not a plugin receiving, and an unprompted call would turn a banner-free site
   * into one with a banner (§12.5) — which is why the SDK tells plugins to call this from a click.
   */
  const request = useCallback((category: string) => {
    setSettingsOpen(true);
    return new Promise<boolean>((resolve) => {
      pending.current.push({ category, resolve });
    });
  }, []);

  const setProgress = useCallback((on: boolean) => {
    setProgressOn(on);
    try {
      if (on) {
        // Stored as an explicit `on` rather than by clearing the key: absence means "no choice made", and
        // under GPC that resolves to off. A visitor who switched it back on has made a choice.
        localStorage.setItem(PROGRESS_PREF_KEY, 'on');
      } else {
        localStorage.setItem(PROGRESS_PREF_KEY, 'off');
        // Switching it off is also a request to forget: leaving the positions behind would keep storing
        // exactly what the visitor just asked not to have stored.
        Object.keys(localStorage)
          .filter((key) => key.startsWith('mc.progress.'))
          .forEach((key) => localStorage.removeItem(key));
      }
    } catch {
      // Same as above: the choice still holds for this page view.
    }
    if (!on) {
      // And on the server, for a signed-in listener.
      //
      // This used to clear the local keys only, so someone signed in kept a server-side record of what they
      // had listened to and how far — indefinitely, and restored on the next play — while the settings page
      // said the positions were deleted. Erasure that stops at the device is not erasure when the data was
      // also sent somewhere. Fire-and-forget: an anonymous visitor has nothing to delete and a 401 here is
      // not something to show them.
      void api.del('/api/me/progress').catch(() => {});
    }
  }, []);

  const value = useMemo<ConsentValue>(
    () => ({
      ...payload,
      has,
      granted,
      request,
      subscribe,
      decide,
      withdraw,
      // A GPC signal is an answer, so it closes the question without a banner — but it is not a *stored*
      // answer, which is why the settings still present it as changeable.
      decided: stored || (gpc && record == null),
      record,
      gpc: gpc && record == null,
      openSettings: () => setSettingsOpen(true),
      closeSettings,
      settingsOpen,
      progressEnabled: progressOn,
      setProgressEnabled: setProgress,
    }),
    [payload, has, granted, request, subscribe, decide, withdraw, stored, record, gpc, settingsOpen,
      closeSettings, progressOn, setProgress],
  );

  return <ConsentContext.Provider value={value}>{children}</ConsentContext.Provider>;
}

export function useConsent() {
  return useContext(ConsentContext);
}
