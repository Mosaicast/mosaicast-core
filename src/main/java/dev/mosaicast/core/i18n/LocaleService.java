// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.i18n.LocaleViews.AdminLocale;
import dev.mosaicast.core.i18n.LocaleViews.AdminLocales;
import dev.mosaicast.core.i18n.LocaleViews.PublicLocale;
import dev.mosaicast.core.i18n.LocaleViews.PublicLocales;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and edits the site's language policy (ARCHITECTURE §12.7).
 *
 * <p>Sits between {@link LocaleRegistry} (what files exist) and {@link SiteConfigService} (what the admin chose)
 * because validating a language list needs both, and neither may depend on the other: the registry reads the
 * config, so a config service that validated against the registry would close the loop.
 */
@Service
public class LocaleService {

    private final LocaleRegistry registry;
    private final SiteConfigService siteConfig;

    public LocaleService(LocaleRegistry registry, SiteConfigService siteConfig) {
        this.registry = registry;
        this.siteConfig = siteConfig;
    }

    /** The public language list: what the switcher offers and what may be authored in. */
    public PublicLocales publicLocales() {
        return new PublicLocales(
                registry.defaultLocale(),
                registry.uiLocales().stream().map(PublicLocale::of).toList(),
                registry.contentLocales().stream().map(PublicLocale::of).toList());
    }

    /**
     * The admin languages page.
     *
     * <p>Rescans the catalog roots first, so an operator who has just copied a file into the drop-in directory
     * sees it by reloading the page rather than by restarting the app.
     */
    public AdminLocales adminLocales() {
        registry.refresh();
        return new AdminLocales(
                LocaleRegistry.SOURCE_LOCALE,
                registry.dropInDir(),
                registry.all().stream().map(AdminLocale::of).toList());
    }

    /**
     * Replaces the language policy.
     *
     * @throws IllegalArgumentException if a code is malformed, a shell language has no catalog, or the default
     *                                  is not one of the content languages
     */
    @Transactional
    public AdminLocales update(List<String> uiLocales, List<String> contentLocales, String defaultLocale) {
        Set<String> ui = clean(uiLocales, "shell");
        Set<String> content = clean(contentLocales, "content");
        String fallback = LocaleRegistry.normalize(defaultLocale);
        if (!LocaleCatalogSource.isCode(fallback)) {
            throw new IllegalArgumentException("'%s' is not a language code".formatted(defaultLocale));
        }

        // The shell can only render a language it has strings for. Content is the opposite case on purpose:
        // an imprint may be written in a language the UI does not speak, so no catalog is required there.
        for (String code : ui) {
            if (registry.catalog(code).isEmpty()) {
                throw new IllegalArgumentException(
                        "No message catalog for '%s' — add %s.json before offering it in the shell"
                                .formatted(code, code));
            }
        }

        // English is the source language and the last fallback; it stays in both lists whatever the form said,
        // so an admin cannot leave the site with no language it is allowed to fall back to.
        ui.add(LocaleRegistry.SOURCE_LOCALE);
        content.add(LocaleRegistry.SOURCE_LOCALE);

        if (!content.contains(fallback)) {
            throw new IllegalArgumentException(
                    "The default language '%s' must be one of the content languages".formatted(fallback));
        }

        siteConfig.updateLocales(new ArrayList<>(ui), new ArrayList<>(content), fallback);
        registry.refresh();
        return adminLocales();
    }

    private static Set<String> clean(List<String> codes, String what) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (codes == null) {
            return cleaned;
        }
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            if (!LocaleCatalogSource.isCode(code)) {
                throw new IllegalArgumentException("'%s' is not a language code (%s)".formatted(code, what));
            }
            cleaned.add(LocaleRegistry.normalize(code));
        }
        return cleaned;
    }
}
