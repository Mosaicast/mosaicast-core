// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

/**
 * The full theme generated from a single accent seed (ARCHITECTURE §12.3): the seed plus a readable
 * light and dark token set.
 *
 * @param accentSeed the admin-chosen accent ({@code #rrggbb})
 * @param light      the light-mode tokens
 * @param dark       the dark-mode tokens
 */
public record GeneratedTheme(String accentSeed, ThemeTokenSet light, ThemeTokenSet dark) {
}
