// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import { isAllowed } from './purge';

/**
 * The core has to obey its own inventory.
 *
 * `CoreStorageInventory` is no longer only the text a visitor reads — it is the allow-list the purge sweeps
 * against, so a key the shell writes and forgets to declare is a key the shell then deletes out from under
 * itself. That is a strange bug to debug and an easy one to introduce: nothing about writing
 * `localStorage.setItem('mc.something', …)` suggests a Java file has to change too.
 *
 * So this reads both sides and compares them. It found `mc.prefs.rate` — written by the player since the
 * inventory existed, disclosed nowhere, and previously invisible because the audit exempted the whole `mc.`
 * namespace from its own rule.
 *
 * It matches literals and template prefixes, which is what the shell actually uses; a key assembled at
 * runtime from parts would slip past, and that is a reason not to build keys that way.
 */

const INVENTORY = '../src/main/java/dev/mosaicast/core/consent/CoreStorageInventory.java';

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      return sourceFiles(path);
    }
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

/**
 * Strips comments, which discuss key names as freely as code uses them — `mc.prefs.*` in a doc block is not a
 * key anybody writes. Only whole-line comments go, so a trailing `//` after code cannot swallow a real key.
 */
function code(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** Every `mc.`-prefixed key the shell writes, whether quoted whole or used as a template prefix. */
function keysWrittenByTheShell(): Set<string> {
  const keys = new Set<string>();
  for (const file of sourceFiles('src')) {
    const source = code(readFileSync(file, 'utf8'));
    for (const [, key] of source.matchAll(/['"`](mc[._][A-Za-z0-9._-]*)/g)) {
      // A template such as `mc.progress.${id}` yields its literal prefix, which the wildcard entry covers.
      keys.add(key);
    }
  }
  return keys;
}

describe('Core storage inventory (§12.5)', () => {
  it('declares every key the shell writes, because the disclosure is also the allow-list', () => {
    const declared = new Set(
      [...readFileSync(INVENTORY, 'utf8').matchAll(/"([^"]+)"/g)]
        .map(([, value]) => value)
        // The file also quotes i18n keys and storage types; only the names matter here.
        .filter((value) => !value.startsWith('consent.') && value !== 'cookie' && value !== 'localStorage'),
    );

    const undeclared = [...keysWrittenByTheShell()].filter((key) => !isAllowed(key, declared));

    expect(undeclared).toEqual([]);
  });
});
