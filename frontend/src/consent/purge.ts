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
 * Everything else goes, including keys no manifest ever mentioned. That is deliberate: an undeclared key is
 * one the privacy settings are actively lying about, and leaving it in place to be polite to a plugin means
 * preferring the plugin's convenience to the visitor's disclosure. Declaring it costs a manifest entry.
 *
 * **Limits, stated plainly.** Only what JavaScript can see. `HttpOnly` cookies never appear in
 * `document.cookie` at all, so one set by a plugin's *backend* is neither swept nor noticed here — that is the
 * server's business. Cookies scoped to a path or a parent domain the shell cannot guess survive; the common
 * cases (`/`, the current path, the host and its registrable parent) are attempted. IndexedDB, the Cache API
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
 * Builds the allow-list from the payload and the decision.
 *
 * A wildcard in a declared name (`mc.progress.*`) covers a family of keys — the only form used, and matched as
 * a prefix rather than a glob because a key is a key, not a path.
 */
export function allowedKeys(payload: ConsentPayload, isGranted: (category: string) => boolean): Set<string> {
  const allowed = new Set<string>();
  payload.essential.storage.forEach((item) => allowed.add(item.name));
  payload.necessaryServices.forEach((service) => service.storage.forEach((item) => allowed.add(item.name)));
  payload.categories
    .filter((category) => isGranted(category.id))
    .forEach((category) =>
      category.services.forEach((service) => service.storage.forEach((item) => allowed.add(item.name))),
    );
  return allowed;
}

/** Whether a key is covered by the allow-list, wildcards included. */
export function isAllowed(key: string, allowed: Set<string>): boolean {
  if (allowed.has(key) || SHELL_OWNED.includes(key)) {
    return true;
  }
  for (const name of allowed) {
    if (name.endsWith('*') && key.startsWith(name.slice(0, -1))) {
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
    doomed.forEach((key) => storage.removeItem(key));
  } catch {
    // Storage disabled entirely — then there is nothing to purge either.
  }
  return doomed;
}

function sweepCookies(allowed: Set<string>): string[] {
  const removed: string[] = [];
  const host = window.location.hostname;
  // A cookie's Path and Domain are not readable from script, only its name and value, so expiry has to be
  // attempted against the scopes a cookie plausibly has. A miss is silent by nature: an expiry for the wrong
  // scope does nothing at all rather than reporting failure.
  const scopes = [
    '',
    '; Path=/',
    `; Path=${window.location.pathname}`,
    `; Path=/; Domain=${host}`,
    `; Path=/; Domain=.${host.split('.').slice(-2).join('.')}`,
  ];

  document.cookie
    .split(';')
    .map((pair) => pair.split('=')[0]?.trim() ?? '')
    .filter((name) => name !== '' && !isAllowed(name, allowed))
    .forEach((name) => {
      removed.push(name);
      scopes.forEach((scope) => {
        document.cookie = `${name}=; Max-Age=0${scope}`;
      });
    });
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
  const allowed = allowedKeys(payload, isGranted);
  return {
    localStorage: sweepStorage(window.localStorage, allowed),
    sessionStorage: sweepStorage(window.sessionStorage, allowed),
    cookies: sweepCookies(allowed),
  };
}
