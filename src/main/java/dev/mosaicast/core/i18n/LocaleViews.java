// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import java.util.List;

/** The payloads the locale API speaks (ARCHITECTURE §12.7). */
public final class LocaleViews {

    private LocaleViews() {
    }

    /**
     * One language, as the shell and plugins see it.
     *
     * <p>Deliberately smaller than {@link LocaleInfo}: catalog origin and translation debt are an
     * operator's concern, and putting them on the public payload would publish which languages an
     * operator half-finished.
     */
    public record PublicLocale(String code, String nativeName, boolean isDefault) {

        static PublicLocale of(LocaleInfo info) {
            return new PublicLocale(info.code(), info.nativeName(), info.isDefault());
        }
    }

    /**
     * The public language list.
     *
     * @param defaultLocale the site default — the last fallback for anything served per locale
     * @param ui            languages the shell can render in, for the language switcher
     * @param content       languages content may be authored in, for per-locale editors (legal, plugins)
     */
    public record PublicLocales(String defaultLocale, List<PublicLocale> ui, List<PublicLocale> content) {
    }

    /**
     * One language on the admin languages page.
     *
     * @param origin      {@code BUNDLED}, {@code DROP_IN} or {@code NONE} — where the catalog came from
     * @param keyCount    how many messages the catalog holds
     * @param missingKeys how many of English's keys it lacks; the admin's translation debt at a glance
     */
    public record AdminLocale(
            String code,
            String nativeName,
            String origin,
            boolean uiEnabled,
            boolean contentEnabled,
            boolean isDefault,
            int keyCount,
            int missingKeys) {

        static AdminLocale of(LocaleInfo info) {
            return new AdminLocale(info.code(), info.nativeName(), info.origin().name(), info.uiEnabled(),
                    info.contentEnabled(), info.isDefault(), info.keyCount(), info.missingKeys());
        }
    }

    /**
     * The admin languages page payload.
     *
     * @param sourceLocale the language every catalog is measured against, and the one that can never be
     *                     switched off
     * @param dropInDir    the configured drop-in directory, or {@code null} — so the page can tell an
     *                     operator where to put a file instead of making them find it in the docs
     */
    public record AdminLocales(String sourceLocale, String dropInDir, List<AdminLocale> locales) {
    }

    /** The languages page's save request. */
    public record UpdateLocales(List<String> uiLocales, List<String> contentLocales, String defaultLocale) {
    }
}
