// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

import type { GeneratedTheme, Mode, ModePolicy, ThemeTokenSet } from '../api/types';

/**
 * Applies the generated theme to the document (ARCHITECTURE §12.3): writes the semantic `--mc-*` custom
 * properties for the active mode onto `:root` and sets `data-theme`. The shell and every plugin read the
 * same properties, so both re-theme together. Kept in sync with the inline no-flash script in `index.html`.
 */

/** Maps each {@link ThemeTokenSet} field to its CSS custom property. */
const TOKEN_VARS: Record<keyof ThemeTokenSet, string> = {
  bg: '--mc-bg',
  surface: '--mc-surface',
  text: '--mc-text',
  textMuted: '--mc-text-muted',
  accent: '--mc-accent',
  accentContrast: '--mc-accent-contrast',
  border: '--mc-border',
  accent2: '--mc-accent-2',
  accentText: '--mc-accent-text',
};

/** Resolves the effective mode: an explicit policy wins; `system` follows the OS preference. */
export function resolveMode(policy: ModePolicy): Mode {
  if (policy === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return policy;
}

/** Writes the mode's tokens to `:root` and stamps `data-theme`. */
export function applyTheme(theme: GeneratedTheme, mode: Mode): void {
  const tokens = mode === 'dark' ? theme.dark : theme.light;
  const root = document.documentElement;
  (Object.keys(TOKEN_VARS) as (keyof ThemeTokenSet)[]).forEach((key) => {
    const value = tokens[key];
    if (value) {
      root.style.setProperty(TOKEN_VARS[key], value);
    }
  });
  root.setAttribute('data-theme', mode);
}
