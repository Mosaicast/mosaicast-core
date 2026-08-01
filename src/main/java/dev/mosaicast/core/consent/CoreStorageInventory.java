// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.consent;

import dev.mosaicast.core.config.SessionConfig;
import java.util.List;

/**
 * What <em>the core itself</em> stores on a visitor's device (ARCHITECTURE §12.5).
 *
 * <p>None of it needs consent — a session cookie, a CSRF token, the chosen language, the cached branding and
 * the consent decision itself are all strictly necessary for the service the visitor asked for, which is why
 * Mosaicast runs banner-free. Necessary is not the same as secret, though: §25 TDDDG / Art. 5(3) ePD are
 * technology-agnostic, so {@code localStorage} has to be disclosed exactly like a cookie (EDPB Guidelines
 * 2/2023). This class is that disclosure.
 *
 * <p><strong>Why a class and not a paragraph in the privacy page.</strong> The seeded privacy markdown listed
 * these by hand and had already drifted — it never mentioned {@code mc.consent}. Prose cannot be kept in step
 * with code by discipline alone. Generating the list from constants means adding a key without adding it here
 * is the only way to drift, and that is a much smaller target.
 *
 * <p>Purposes and durations travel to the shell as <strong>i18n keys</strong>, not as English sentences: the
 * disclosure has to read correctly in every UI locale, and only the shell knows which one is active.
 */
public final class CoreStorageInventory {

    /** The key the shell stores the visitor's consent decision under. */
    public static final String CONSENT_KEY = "mc.consent";

    /** The key the shell stores the playback-position opt-out under. */
    public static final String PROGRESS_PREF_KEY = "mc.prefs.progress";

    private CoreStorageInventory() {
    }

    /**
     * One item core stores, as the shell renders it.
     *
     * @param name        the cookie or storage key exactly as it appears on the device
     * @param type        {@code cookie} or {@code localStorage}
     * @param purposeKey  i18n key for the plain-language purpose
     * @param durationKey i18n key for how long it lasts
     * @param optional    whether the visitor can switch it off without breaking the service — true only for
     *                    listening progress, which gets a switch rather than a consent gate because it is
     *                    first-party, local, never profiled, and written only after a deliberate press of play
     */
    public record Item(String name, String type, String purposeKey, String durationKey, boolean optional) {
    }

    private static final List<Item> ITEMS = List.of(
            new Item(SessionConfig.SESSION_COOKIE_NAME, "cookie",
                    "consent.purpose.session", "consent.duration.session", false),
            new Item("XSRF-TOKEN", "cookie",
                    "consent.purpose.csrf", "consent.duration.session", false),
            new Item("mc.locale", "localStorage",
                    "consent.purpose.locale", "consent.duration.persistent", false),
            new Item("mc.site", "localStorage",
                    "consent.purpose.site", "consent.duration.persistent", false),
            new Item(CONSENT_KEY, "localStorage",
                    "consent.purpose.consent", "consent.duration.months12", false),
            // The same decision, mirrored where an HTTP response can read it — see ConsentCookie.
            new Item(ConsentCookie.NAME, "cookie",
                    "consent.purpose.consentCookie", "consent.duration.months12", false),
            new Item("mc.progress.*", "localStorage",
                    "consent.purpose.progress", "consent.duration.persistent", true),
            new Item(PROGRESS_PREF_KEY, "localStorage",
                    "consent.purpose.progressPref", "consent.duration.persistent", false));

    /** Every item core stores, in the order the disclosure lists them. */
    public static List<Item> items() {
        return ITEMS;
    }
}
