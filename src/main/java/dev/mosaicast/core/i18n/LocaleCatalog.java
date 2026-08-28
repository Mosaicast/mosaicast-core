// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One language's message catalog: the flat dotted keys of {@code locales/&lt;code&gt;.json} (ARCHITECTURE
 * §12.7), plus where the file came from.
 *
 * @param code     the locale code, lower-cased ({@code "de"}, {@code "pt-br"})
 * @param messages the merged keys, drop-in over bundled, in file order
 * @param origin   which root supplied the file — shown in the admin so an operator can tell a shipped
 *                 language from one they dropped in themselves
 */
public record LocaleCatalog(String code, Map<String, String> messages, Origin origin) {

    /** Where a catalog came from. */
    public enum Origin {
        /** Shipped with the release, from the classpath. */
        BUNDLED,
        /** Found in {@code MOSAICAST_LOCALES_DIR}, possibly overriding a bundled file. */
        DROP_IN,
        /**
         * No catalog at all. A locale can still be enabled for *content* — an operator may want a Dutch
         * imprint without a Dutch UI — so the registry has to be able to name a language it cannot render.
         */
        NONE
    }

    public LocaleCatalog {
        messages = Map.copyOf(messages);
    }

    /** An entry for a language the admin enabled for content but for which no file exists. */
    public static LocaleCatalog absent(String code) {
        return new LocaleCatalog(code, new LinkedHashMap<>(), Origin.NONE);
    }

    public boolean present() {
        return origin != Origin.NONE;
    }
}
