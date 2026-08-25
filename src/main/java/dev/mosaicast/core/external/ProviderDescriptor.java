// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.external;

import dev.mosaicast.core.external.settings.SettingsManifest;
import java.time.Duration;

/**
 * What a provider is, and what it needs configured (ARCHITECTURE §12.7). Constant for the life of the bean.
 *
 * <p>Most of this exists to be shown to an admin <em>before</em> they select it. Choosing a translation
 * provider means deciding whose servers the site's text is sent to, and that is a decision the operator
 * should make with the facts in front of them rather than discover afterwards.
 *
 * @param id                   stable, lower-case, no spaces — the stored value and the URL segment
 * @param kind                 which admin section it appears in
 * @param name                 English display name; the UI prefers a catalog key and falls back to this
 * @param description          one English sentence, same treatment
 * @param homepage             where to read about it, or {@code null}
 * @param privacyUrl           the provider's privacy policy; {@code null} for self-hosted, where there is no
 *                             third party to have one
 * @param selfHosted           whether the operator runs it themselves — drives the private-origin allowance
 *                             and what the admin page says about data leaving the building
 * @param paid                 whether using it costs money
 * @param thirdCountryTransfer whether using it sends content outside the EU (§12.5's vocabulary)
 * @param cacheable            whether results may be cached; {@code false} switches the cache decorator off
 * @param unit                 what usage is counted in: {@code characters}, {@code seconds}, {@code tokens}
 * @param defaultRequestsPerMinute the provider's own polite default, overridable by a setting
 * @param defaultTimeout       the wall-clock budget for one call
 * @param settings             what the admin must fill in
 */
public record ProviderDescriptor(
        String id,
        ExternalServiceKind kind,
        String name,
        String description,
        String homepage,
        String privacyUrl,
        boolean selfHosted,
        boolean paid,
        boolean thirdCountryTransfer,
        boolean cacheable,
        String unit,
        int defaultRequestsPerMinute,
        Duration defaultTimeout,
        SettingsManifest settings) {

    /** A provider id that can be a database value, a URL segment and an i18n key suffix unescaped. */
    static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{1,39}$");

    public ProviderDescriptor {
        settings = settings == null ? SettingsManifest.empty() : settings;
    }
}
