// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import java.util.Locale;

/**
 * One language as the rest of the system sees it (ARCHITECTURE §12.7).
 *
 * <p><strong>UI and content are two different sets, on purpose.</strong> {@code uiEnabled} means the shell can
 * render in this language; {@code contentEnabled} means the admin permits text to be <em>authored</em> in it —
 * legal pages, the About blurb, and whatever a plugin stores per locale. They overlap in the common case and
 * come apart in the ones that matter: an operator can want a Dutch imprint with an English-only UI, or ship
 * twelve catalogs and author in two.
 *
 * @param code           the locale code, lower-cased
 * @param nativeName     the language's name in its own language ({@code "Nederlands"}), for menus
 * @param origin         where the catalog came from, or {@link LocaleCatalog.Origin#NONE} when there is none
 * @param uiEnabled      whether the shell offers this language
 * @param contentEnabled whether content may be authored in this language
 * @param isDefault      whether this is the site default (§12.7's last fallback)
 * @param keyCount       how many messages the catalog holds
 * @param missingKeys    how many of the source language's keys it is missing — the admin's translation debt
 */
public record LocaleInfo(
        String code,
        String nativeName,
        LocaleCatalog.Origin origin,
        boolean uiEnabled,
        boolean contentEnabled,
        boolean isDefault,
        int keyCount,
        int missingKeys) {

    /**
     * The language's own name for itself, falling back to the upper-cased code.
     *
     * <p>Derived rather than tabulated: a hardcoded map only names the languages whoever wrote it thought of,
     * which is precisely the wrong property for a registry whose whole point is that an operator can add a
     * language nobody here anticipated.
     */
    public static String nativeNameOf(String code) {
        Locale locale = Locale.forLanguageTag(code);
        String name = locale.getDisplayLanguage(locale);
        return name == null || name.isBlank() || name.equalsIgnoreCase(code)
                ? code.toUpperCase(Locale.ROOT)
                : name;
    }
}
