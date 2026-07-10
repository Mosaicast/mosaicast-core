// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

/**
 * The site's light/dark mode policy (ARCHITECTURE §12.1/§12.3): force a mode, or follow the visitor's
 * system preference.
 */
public enum ModePolicy {
    LIGHT,
    DARK,
    SYSTEM
}
