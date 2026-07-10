// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// No theme flash (ARCHITECTURE §12.3): before first paint, set data-theme + the accent tokens
// synchronously from the cached site payload (written by SiteContext on the previous load). First-ever
// visit has no cache -> fall back to the OS preference and the CSS default tokens. Served same-origin so
// it satisfies the strict `script-src 'self'` CSP (an inline script would be blocked). Loaded as a
// blocking <script src> in <head>, so it still runs before the body paints. Kept in sync with
// src/theme/applyTheme.ts (same token->var map).
(function () {
  try {
    var root = document.documentElement;
    var raw = localStorage.getItem('mc.site');
    if (raw) {
      var site = JSON.parse(raw);
      var policy = site.modePolicy;
      var mode =
        policy === 'system'
          ? matchMedia('(prefers-color-scheme: dark)').matches
            ? 'dark'
            : 'light'
          : policy;
      var tokens = mode === 'dark' ? site.theme.dark : site.theme.light;
      var map = {
        bg: '--mc-bg',
        surface: '--mc-surface',
        text: '--mc-text',
        textMuted: '--mc-text-muted',
        accent: '--mc-accent',
        accentContrast: '--mc-accent-contrast',
        border: '--mc-border',
        accent2: '--mc-accent-2',
      };
      for (var key in map) {
        if (tokens[key]) {
          root.style.setProperty(map[key], tokens[key]);
        }
      }
      root.setAttribute('data-theme', mode);
    } else {
      root.setAttribute('data-theme', matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    }
  } catch (e) {
    /* theming is non-critical; the CSS defaults keep the shell readable */
  }
})();
