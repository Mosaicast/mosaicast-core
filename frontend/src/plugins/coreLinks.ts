// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { PluginLinks } from '@mosaicast/plugin-sdk';

/**
 * The host's own URL shapes, handed to plugins as `ctx.links` (ARCHITECTURE §6.4).
 *
 * This lives in core rather than in the SDK for the same reason `makePluginApi` does: the routes are the
 * host's, so a change to them should ship with the host that serves them rather than waiting on a contract
 * release. The SDK owns the *type*; this owns the answer.
 *
 * **Strings, not navigation.** Nothing here moves the shell — `ctx.route.navigate` is the only outbound
 * handle a plugin has, and it stays confined to `/p/<pluginId>/`. Producing a link is a different act: the
 * visitor clicks it, and a real `href` is what middle-click, "open in new tab" and crawlers need.
 */
export const coreLinks: PluginLinks = {
  episode(slug, opts) {
    const t = opts?.t;
    // Zero is the start, which a bare link already means — carrying `?t=0` would make one moment two URLs.
    const at = t != null && Number.isFinite(t) && t > 0 ? `?t=${Math.floor(t)}` : '';
    return `/episodes/${encodeURIComponent(slug)}${at}`;
  },

  feed(slug, opts) {
    const params = new URLSearchParams();
    if (opts?.season) {
      params.set('season', opts.season);
    }
    if (opts?.tag) {
      params.set('tag', opts.tag);
    }
    // `newest` is what the shell falls back to when the parameter is absent, and what `SiteUrls`
    // canonicalizes to, so carrying it would make one view canonicalize two ways (§6.1).
    if (opts?.order === 'oldest') {
      params.set('order', 'oldest');
    }
    const query = params.toString();
    return `/feeds/${encodeURIComponent(slug)}${query ? `?${query}` : ''}`;
  },
};
