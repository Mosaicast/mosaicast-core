// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { ConsentPayload } from '../api/types';

/**
 * Warns, in the dev profile only, when something writes a storage key nobody declared.
 *
 * **This is detection, not containment, and the difference matters.** A plugin bundle is loaded with
 * `import()` into the page's own JavaScript realm — shadow DOM encapsulates styles and markup, never
 * capabilities — so a plugin can reach `localStorage`, `document.cookie` and `fetch` exactly as the shell
 * can. Nothing running in that realm can take those away from it: a patched `setItem` is one
 * `delete window.localStorage.setItem` away from being unpatched, and this makes no attempt to defend
 * against a plugin that goes looking. Real containment would mean an iframe per plugin, which is an
 * architecture decision (§7.5 chose Web Components), not a patch.
 *
 * What it is for: catching the ordinary case, where a plugin stores something its manifest never mentioned —
 * usually a bug, sometimes an author who forgot that the declaration is also the disclosure a visitor reads.
 * Undeclared storage means the privacy settings are lying to visitors, and that is worth finding during
 * development rather than after deployment.
 *
 * Attribution is a best effort: the stack is matched against `/plugins/<id>/assets/…`, the URL a plugin
 * bundle is served from. A write from a callback the plugin scheduled may have lost that frame, in which
 * case the entry is reported without a name rather than blamed on the wrong plugin.
 */

/** Keys the shell itself writes; the inventory covers the rest. */
const CORE_PREFIXES = ['mc.', 'mc_'];

let installed = false;

export function installStorageAudit(payload: ConsentPayload): void {
  if (installed) {
    return;
  }
  installed = true;

  const declared = new Set<string>();
  payload.essential.storage.forEach((item) => declared.add(item.name));
  payload.categories.forEach((category) =>
    category.services.forEach((service) => service.storage.forEach((item) => declared.add(item.name))),
  );

  const isDeclared = (key: string) =>
    declared.has(key) ||
    CORE_PREFIXES.some((prefix) => key.startsWith(prefix)) ||
    // Wildcards in the inventory (`mc.progress.*`) cover a family of keys.
    [...declared].some((name) => name.endsWith('*') && key.startsWith(name.slice(0, -1)));

  const blame = (): string => {
    const frame = new Error().stack?.split('\n').find((line) => line.includes('/plugins/'));
    return frame?.match(/\/plugins\/([^/]+)\//)?.[1] ?? 'unknown source';
  };

  const report = (kind: string, key: string) => {
    if (isDeclared(key)) {
      return;
    }
    console.warn(
      `[mosaicast] undeclared ${kind} "${key}" written by ${blame()}. ` +
        'Visitors are shown the manifest declaration as the full list of what is stored — add it to ' +
        'consent.services[].storage, or stop writing it.',
    );
  };

  for (const [name, storage] of [
    ['localStorage', window.localStorage],
    ['sessionStorage', window.sessionStorage],
  ] as const) {
    const original = storage.setItem.bind(storage);
    storage.setItem = (key: string, value: string) => {
      report(name, key);
      original(key, value);
    };
  }

  const cookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
  if (cookie?.set && cookie.get) {
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => cookie.get!.call(document),
      set: (value: string) => {
        report('cookie', String(value).split('=')[0]?.trim() ?? '');
        cookie.set!.call(document, value);
      },
    });
  }
}
