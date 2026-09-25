// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

/**
 * The semantic theme tokens for one mode (ARCHITECTURE §12.3), as {@code #rrggbb} strings. The shell and
 * plugins read the same {@code --mc-*} custom properties, so both re-theme together. One set per mode
 * (light/dark).
 *
 * @param bg             page background ({@code --mc-bg})
 * @param surface        raised surface ({@code --mc-surface})
 * @param text           primary text ({@code --mc-text})
 * @param textMuted      secondary text ({@code --mc-text-muted})
 * @param accent         accent ({@code --mc-accent})
 * @param accentContrast readable text on the accent ({@code --mc-accent-contrast})
 * @param border         borders/dividers ({@code --mc-border})
 * @param accent2        secondary accent ({@code --mc-accent-2})
 * @param accentText     the accent as a <em>foreground</em> — links, the active tab, the focus ring
 *                       ({@code --mc-accent-text}) — clamped to WCAG AA against both {@code bg} and
 *                       {@code surface}. {@code accent} stays the raw seed: it is paired with
 *                       {@code accentContrast} as a background, which is where it belongs, and it is a
 *                       plugin-facing token whose meaning does not change (core#162).
 */
public record ThemeTokenSet(
        String bg,
        String surface,
        String text,
        String textMuted,
        String accent,
        String accentContrast,
        String border,
        String accent2,
        String accentText) {
}
