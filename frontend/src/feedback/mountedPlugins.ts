// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

/**
 * Which plugins have something mounted on the current page — what a feedback report offers as its context
 * (ARCHITECTURE §20.2). The server keeps only ids that are actually installed and adds their versions, so this
 * is a proposal, and a cheap one: a counter per id, kept in step by `PluginMount`.
 */
const mounted = new Map<string, number>();

/** Notes a mount; the returned function notes its unmount. */
export function noteMounted(pluginId: string): () => void {
  mounted.set(pluginId, (mounted.get(pluginId) ?? 0) + 1);
  return () => {
    const left = (mounted.get(pluginId) ?? 1) - 1;
    if (left > 0) {
      mounted.set(pluginId, left);
    } else {
      mounted.delete(pluginId);
    }
  };
}

/** The ids mounted right now. */
export function mountedPluginIds(): string[] {
  return [...mounted.keys()];
}
