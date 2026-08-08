// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { ConsentPayload } from '../api/types';

/**
 * Deletes everything on the device that nothing declared and nobody granted.
 *
 * **This is the half of consent enforcement that does not need the plugin's cooperation.** Blocking a write
 * is hopeless in a shared JavaScript realm — a patched `localStorage.setItem` is one same-origin iframe away
 * from being bypassed, since `iframe.contentWindow.localStorage` is an unpatched object writing into the very
 * same storage bucket. Deleting is a different verb: the shell owns this origin too, so it can remove a key
 * whatever wrote it and however that write got past. A plugin can win the race on any single write and still
 * lose the key.
 *
 * That matters because withdrawal is not "stop sending data" — it is Art. 17 and "as easy as granting". Until
 * now a visitor who withdrew got a narrowed CSP, so the service could no longer phone home, while everything
 * it had already written stayed on their device indefinitely. The CSP closed the future; this closes the past.
 *
 * **What survives a sweep**, and nothing else:
 * - core's own storage, from `CoreStorageInventory` (`essential.storage`);
 * - what services in a *granted* category declared;
 * - what services declared as `necessary` declared — never offered as a choice, so no decision removes them.
 *
 * In **`localStorage` and `sessionStorage`, everything else goes**, including keys no manifest ever mentioned.
 * That is deliberate: an undeclared key is one the privacy settings are actively lying about, and leaving it
 * in place to be polite to a plugin means preferring the plugin's convenience to the visitor's disclosure.
 * Declaring it costs a manifest entry. The shell can afford that rule there because those two buckets belong
 * to this origin and nothing but the shell and its plugins writes into them.
 *
 * **Cookies are swept by a narrower rule: only names some manifest declared.** The same-origin argument does
 * not hold for them. A cookie is scoped by host *and* by domain, so `document.cookie` on
 * `podcasts.example.com` also shows what `example.com` set for the whole estate — an SSO session, a
 * load-balancer affinity cookie, an operator's own tag. None of those are Mosaicast's to delete, and expiring
 * them is not privacy enforcement, it is breaking someone else's application. So an undeclared cookie is left
 * alone and a declared one is removed exactly when its category is refused. Withdrawal still reaches
 * everything consent actually governs, because governing it required declaring it in the first place.
 *
 * For the same reason the sweep never writes a `Domain=` wider than the current host. A parent-domain expiry
 * is precisely the operation that reaches other people's applications, and the registrable domain cannot be
 * derived from a hostname anyway without the public-suffix list — `example.co.uk` yields `.co.uk`, which
 * browsers reject outright.
 *
 * **Limits, stated plainly.** Only what JavaScript can see. `HttpOnly` cookies never appear in
 * `document.cookie` at all, so one set by a plugin's *backend* is neither swept nor noticed here — that is the
 * server's business. A declared cookie the plugin scoped to a path or a domain the shell does not attempt
 * survives; the plausible cases (`/`, the current path, the exact host) are tried. IndexedDB, the Cache API
 * and OPFS are not swept; a plugin using those is out of reach of this and of the audit both.
 */

/** Where the shell keeps the decision itself. Defined here because the sweep has to know them first. */
export const STORAGE_KEY = 'mc.consent';
export const PROGRESS_PREF_KEY = 'mc.prefs.progress';
export const CONSENT_COOKIE = 'mc_consent';

/**
 * Keys a sweep may never take, whatever the payload says.
 *
 * These three *are* the consent machinery: the receipt, the server's copy of it, and the playback-position
 * switch. They are disclosed through `CoreStorageInventory` like everything else, but the disclosure must not
 * be what authorises them to exist — otherwise a server that dropped an inventory entry, or a payload that
 * arrived half-formed, would delete the visitor's own decision and silently re-ask a question they had already
 * answered. A sweep that can erase the decision it is enforcing is a loop, not a rule.
 */
const SHELL_OWNED = [STORAGE_KEY, PROGRESS_PREF_KEY, CONSENT_COOKIE];

export interface PurgeResult {
  localStorage: string[];
  sessionStorage: string[];
  cookies: string[];
}

/**
 * Collects declared storage names into a set, skipping anything that is not a usable name.
 *
 * A manifest reaches this function having been parsed into an all-optional record, so `name` can be null — a
 * plugin author who wrote `"key"` where the schema says `"name"` produces exactly that, and the server has no
 * reason to reject the rest of an otherwise valid service over it. Letting the null through would put it in the
 * allow-list, where `name.endsWith` throws and takes the whole sweep down with it. Dropping it here means the
 * item simply does not authorise anything, which is the safe reading of "we could not tell what you declared".
 */
function collect(into: Set<string>, items: readonly { name: string }[] | undefined): void {
  items?.forEach((item) => {
    if (typeof item?.name === 'string' && item.name !== '') {
      into.add(item.name);
    }
  });
}

/**
 * Builds the allow-list from the payload and the decision.
 *
 * A wildcard in a declared name (`mc.progress.*`) covers a family of keys — the only form used, and matched as
 * a prefix rather than a glob because a key is a key, not a path.
 */
export function allowedKeys(payload: ConsentPayload, isGranted: (category: string) => boolean): Set<string> {
  const allowed = new Set<string>();
  collect(allowed, payload.essential?.storage);
  payload.necessaryServices?.forEach((service) => collect(allowed, service.storage));
  payload.categories
    ?.filter((category) => isGranted(category.id))
    .forEach((category) => category.services?.forEach((service) => collect(allowed, service.storage)));
  return allowed;
}

/**
 * Every storage name any manifest declared, granted or not.
 *
 * The cookie sweep needs this because its rule is the inverse of the storage sweep's: it removes what was
 * declared and refused rather than what was never declared at all (see the module comment). A name appearing
 * here is a name the shell has a mandate over.
 */
export function declaredNames(payload: ConsentPayload): Set<string> {
  const declared = new Set<string>();
  collect(declared, payload.essential?.storage);
  payload.necessaryServices?.forEach((service) => collect(declared, service.storage));
  payload.categories?.forEach((category) =>
    category.services?.forEach((service) => collect(declared, service.storage)),
  );
  return declared;
}

/**
 * Whether a key is covered by the allow-list, wildcards included.
 *
 * A wildcard needs a prefix to match against. A declared name of `"*"` would otherwise reduce to
 * `key.startsWith('')`, which is true of every key on the origin — one manifest line silently switching off
 * both this sweep and the storage audit that shares the predicate, for core's keys as much as the plugin's own.
 * The form the shell itself uses (`mc.progress.*`) is unaffected.
 */
export function isAllowed(key: string, allowed: Set<string>): boolean {
  if (allowed.has(key) || SHELL_OWNED.includes(key)) {
    return true;
  }
  for (const name of allowed) {
    if (name.length > 1 && name.endsWith('*') && key.startsWith(name.slice(0, -1))) {
      return true;
    }
  }
  return false;
}

function sweepStorage(storage: Storage, allowed: Set<string>): string[] {
  const doomed: string[] = [];
  try {
    // Snapshot the keys first: removing while enumerating a live Storage reindexes it and skips entries.
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (key !== null && !isAllowed(key, allowed)) {
        doomed.push(key);
      }
    }
  } catch {
    // Storage disabled entirely — then there is nothing to enumerate either.
  }
  // Deliberately outside the catch above: a sweep that identified keys and then skipped deleting them is
  // withdrawal silently not being enforced, which is the one failure mode this module must not have.
  try {
    doomed.forEach((key) => storage.removeItem(key));
  } catch {
    // Nothing more to do — the caller reports what was identified, not what the browser allowed.
  }
  return doomed;
}

function sweepCookies(allowed: Set<string>, declared: Set<string>): string[] {
  const removed: string[] = [];
  const host = window.location.hostname;
  // A cookie's Path and Domain are not readable from script, only its name and value, so expiry has to be
  // attempted against the scopes a cookie plausibly has. A miss is silent by nature: an expiry for the wrong
  // scope does nothing at all rather than reporting failure. Nothing here goes wider than the current host —
  // see the module comment for why a parent-domain expiry is not the shell's to attempt.
  const scopes = ['', '; Path=/', `; Path=${window.location.pathname}`, `; Path=/; Domain=${host}`];

  try {
    document.cookie
      .split(';')
      .map((pair) => pair.split('=')[0]?.trim() ?? '')
      .filter((name) => name !== '' && isAllowed(name, declared) && !isAllowed(name, allowed))
      .forEach((name) => {
        removed.push(name);
        scopes.forEach((scope) => {
          document.cookie = `${name}=; Max-Age=0${scope}`;
        });
      });
  } catch {
    // Cookies disabled entirely — then there is nothing to purge either.
  }
  return removed;
}

/**
 * Sweeps the device down to the allow-list and reports what went.
 *
 * Runs after every decision *and* on load: a plugin uninstalled while the visitor was away leaves keys that no
 * decision of theirs will ever touch again, and the next page view is the first chance to notice.
 *
 * Returns the removed keys so the caller can log them in dev — a plugin author whose storage keeps vanishing
 * deserves to be told why, and told the fix (declare it) rather than left to guess.
 */
export function purgeUndeclared(
  payload: ConsentPayload,
  isGranted: (category: string) => boolean,
): PurgeResult {
  // No payload means the request failed or has not landed. Purging on an empty allow-list would delete the
  // visitor's own settings every time the API hiccups, so an unknown declaration means leave everything alone.
  if (!payload.fingerprint) {
    return { localStorage: [], sessionStorage: [], cookies: [] };
  }
  // The sweep runs from an effect mounted above the router's error boundary, so anything thrown here unmounts
  // the entire shell to a blank page rather than one route. A malformed manifest must cost the visitor a
  // missing purge, never the site.
  try {
    const allowed = allowedKeys(payload, isGranted);
    const declared = declaredNames(payload);
    return {
      localStorage: sweepStorage(window.localStorage, allowed),
      sessionStorage: sweepStorage(window.sessionStorage, allowed),
      cookies: sweepCookies(allowed, declared),
    };
  } catch {
    return { localStorage: [], sessionStorage: [], cookies: [] };
  }
}
