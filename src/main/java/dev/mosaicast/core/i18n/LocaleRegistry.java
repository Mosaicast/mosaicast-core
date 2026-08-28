// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import dev.mosaicast.core.branding.SiteConfig;
import dev.mosaicast.core.branding.SiteConfigService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which languages this instance has, and what each is allowed to be used for (ARCHITECTURE §12.7).
 *
 * <p>This is the single source of truth for the language menu, the site default, the per-locale tabs in the
 * legal editor, and the locale list a plugin reads through {@code ctx.locale}. Before it existed the answer
 * was "whichever JSON files someone imported into {@code i18n.ts}" — a build-time constant that no operator
 * could change and no plugin could see.
 *
 * <p><strong>A snapshot, replaced wholesale.</strong> Same shape as {@code ExternalMediaHostRegistry}: reads
 * are lock-free off a {@code volatile} field, a refresh recomputes and swaps, and a failed refresh keeps the
 * previous answer. A stale language list is a cosmetic problem for one admin; an exception on this path would
 * be a 500 on every page, because the footer and the language switcher both go through here.
 */
@Component
public class LocaleRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocaleRegistry.class);

    /** English is the source language (§12.7): every other catalog is measured against it. */
    public static final String SOURCE_LOCALE = "en";

    private final LocaleCatalogSource catalogs;
    private final SiteConfigService siteConfig;

    /** The last computed view. Read on every request, replaced wholesale by {@link #refresh()}. */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public LocaleRegistry(LocaleCatalogSource catalogs, SiteConfigService siteConfig) {
        this.catalogs = catalogs;
        this.siteConfig = siteConfig;
    }

    /** The computed view: what the files hold, crossed with what the admin enabled. */
    private record Snapshot(List<LocaleInfo> locales, Map<String, LocaleCatalog> catalogs, String defaultLocale) {
        private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), SOURCE_LOCALE);
    }

    /**
     * Rescans both catalog roots and recomputes the view.
     *
     * <p>Called at startup and again whenever the admin opens or saves the languages page, which is what makes
     * a dropped-in file appear without a restart. Not scheduled: a directory an operator edits by hand changes
     * a few times in a site's life, and a poll would be a database read every tick to notice nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            this.snapshot = compute();
            log.debug("Locale registry refreshed: {} language(s)", snapshot.locales().size());
        } catch (RuntimeException problem) {
            log.warn("Could not refresh the locale registry; keeping {} language(s) from before",
                    snapshot.locales().size(), problem);
        }
    }

    private Snapshot compute() {
        Map<String, LocaleCatalog> found = new TreeMap<>(catalogs.scan());
        SiteConfig config = siteConfig.get();
        String defaultLocale = normalize(config.getDefaultLocale());
        Set<String> ui = normalizeAll(config.getUiLocales());
        Set<String> content = normalizeAll(config.getContentLocales());

        // A language the admin enabled for content but whose file is gone (or was never there) still has to
        // appear, or the imprint written in it becomes invisible and un-editable rather than merely untranslated.
        Set<String> codes = new LinkedHashSet<>(found.keySet());
        codes.addAll(ui);
        codes.addAll(content);
        codes.add(SOURCE_LOCALE);
        codes.add(defaultLocale);

        int sourceKeys = found.containsKey(SOURCE_LOCALE) ? found.get(SOURCE_LOCALE).messages().size() : 0;
        List<LocaleInfo> locales = new ArrayList<>();
        for (String code : codes.stream().sorted().toList()) {
            LocaleCatalog catalog = found.getOrDefault(code, LocaleCatalog.absent(code));
            int missing = Math.max(0, sourceKeys - catalog.messages().size());
            locales.add(new LocaleInfo(
                    code,
                    LocaleInfo.nativeNameOf(code),
                    catalog.origin(),
                    // The source language is always renderable: it is compiled into the shell either way, so
                    // an admin who disabled every language would otherwise be left with a UI in no language.
                    (ui.contains(code) || SOURCE_LOCALE.equals(code)) && catalog.present(),
                    content.contains(code) || SOURCE_LOCALE.equals(code) || defaultLocale.equals(code),
                    defaultLocale.equals(code),
                    catalog.messages().size(),
                    missing));
        }
        return new Snapshot(List.copyOf(locales), Map.copyOf(found), defaultLocale);
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current.locales().isEmpty()) {
            // Only before the first successful refresh — a request that arrives before ApplicationReady, or
            // after a startup where the database was not up yet.
            refresh();
            current = snapshot;
        }
        return current;
    }

    /** Every known language, ordered by code. */
    public List<LocaleInfo> all() {
        return current().locales();
    }

    /** The languages the shell can render in. */
    public List<LocaleInfo> uiLocales() {
        return all().stream().filter(LocaleInfo::uiEnabled).toList();
    }

    /** The languages content may be authored in. */
    public List<LocaleInfo> contentLocales() {
        return all().stream().filter(LocaleInfo::contentEnabled).toList();
    }

    public boolean isUiLocale(String code) {
        String wanted = normalize(code);
        return uiLocales().stream().anyMatch(locale -> locale.code().equals(wanted));
    }

    public boolean isContentLocale(String code) {
        String wanted = normalize(code);
        return contentLocales().stream().anyMatch(locale -> locale.code().equals(wanted));
    }

    /** The site default; the ultimate fallback for content served per locale (§12.7). */
    public String defaultLocale() {
        return current().defaultLocale();
    }

    /** The configured drop-in directory as text, or {@code null} when there is none. */
    public String dropInDir() {
        return catalogs.dropInDir() == null ? null : catalogs.dropInDir().toString();
    }

    /** The messages for a language, if it has a catalog at all. */
    public Optional<LocaleCatalog> catalog(String code) {
        return Optional.ofNullable(current().catalogs().get(normalize(code)));
    }

    /** Lower-cases and trims a code; a blank one becomes the source language rather than an empty key. */
    static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return SOURCE_LOCALE;
        }
        return code.trim().toLowerCase(Locale.ROOT);
    }

    static Set<String> normalizeAll(List<String> codes) {
        if (codes == null) {
            return Set.of();
        }
        return codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(LocaleRegistry::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
