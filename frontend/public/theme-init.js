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
  } catch {
    /* theming is non-critical; the CSS defaults keep the shell readable */
  }
  // The visitor's own language choice, before first paint (core#171). The server can only write what it
  // knows — `?lang=` or the site default — while the choice lives on the device. A `?lang=` in the URL wins,
  // exactly as it does in i18n.ts, so the two never disagree; i18n.ts keeps it current after that.
  try {
    if (!new URLSearchParams(location.search).get('lang')) {
      var chosen = localStorage.getItem('mc.locale');
      if (chosen && /^[a-z]{2,3}$/.test(chosen)) {
        document.documentElement.setAttribute('lang', chosen);
      }
    }
  } catch {
    /* no stored choice readable: the server's value stands */
  }
})();
