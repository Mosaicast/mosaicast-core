// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import dev.mosaicast.core.branding.SiteConfigService;
import dev.mosaicast.core.legal.LegalViews.AdminPage;
import dev.mosaicast.core.legal.LegalViews.AdminTranslation;
import dev.mosaicast.core.legal.LegalViews.FooterEntry;
import dev.mosaicast.core.legal.LegalViews.RenderedPage;
import java.util.Comparator;
import dev.mosaicast.core.web.ConflictException;
import dev.mosaicast.core.web.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal-pages mini-CMS (ARCHITECTURE §12.6): admin CRUD over pages and their per-locale markdown, plus
 * the public footer list and rendered page. Bodies are served in the requested locale with fallback to the
 * default locale (§12.7); markdown is rendered sanitized.
 */
@Service
public class LegalService {

    private static final Logger log = LoggerFactory.getLogger(LegalService.class);

    /** The ultimate fallback locale when the site default is unset/unavailable (§12.7). */
    public static final String DEFAULT_LOCALE = "en";

    private final LegalPageRepository pages;
    private final LegalPageTranslationRepository translations;
    private final MarkdownRenderer markdown;
    private final SiteConfigService siteConfig;

    public LegalService(LegalPageRepository pages, LegalPageTranslationRepository translations,
                        MarkdownRenderer markdown, SiteConfigService siteConfig) {
        this.pages = pages;
        this.translations = translations;
        this.markdown = markdown;
        this.siteConfig = siteConfig;
    }

    /** The configured site default language, falling back to {@link #DEFAULT_LOCALE} if unavailable. */
    private String fallbackLocale() {
        try {
            String locale = siteConfig.get().getDefaultLocale();
            return locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
        } catch (RuntimeException e) {
            return DEFAULT_LOCALE;
        }
    }

    // ---- admin read ----

    /** Every page with all its locales' raw title + markdown, for the admin editor (§12.6). */
    @Transactional(readOnly = true)
    public List<AdminPage> adminList() {
        return pages.findAllByOrderBySortOrderAscSlugAsc().stream()
                .map(page -> new AdminPage(
                        page.getSlug(), page.getRoleMarker(), page.getSortOrder(),
                        translations.findByPageId(page.getId()).stream()
                                .sorted(Comparator.comparing(LegalPageTranslation::getLocale))
                                .map(tr -> new AdminTranslation(tr.getLocale(), tr.getTitle(), tr.getMarkdown()))
                                .toList()))
                .toList();
    }

    // ---- public read ----

    /** Footer links for the locale (pages with no usable translation are omitted). */
    @Transactional(readOnly = true)
    public List<FooterEntry> footer(String locale) {
        List<FooterEntry> entries = new ArrayList<>();
        for (LegalPage page : pages.findAllByOrderBySortOrderAscSlugAsc()) {
            resolveTranslation(page, locale).ifPresent(t ->
                    entries.add(new FooterEntry(page.getSlug(), t.getTitle(), page.getRoleMarker())));
        }
        return entries;
    }

    /** Renders a page in the locale (with fallback), or 404. */
    @Transactional(readOnly = true)
    public RenderedPage render(String slug, String locale) {
        LegalPage page = requirePage(slug);
        LegalPageTranslation t = resolveTranslation(page, locale)
                .orElseThrow(() -> new NotFoundException("Page has no content: " + slug));
        return new RenderedPage(page.getSlug(), t.getTitle(), page.getRoleMarker(),
                markdown.toSafeHtml(t.getMarkdown()));
    }

    private Optional<LegalPageTranslation> resolveTranslation(LegalPage page, String locale) {
        Optional<LegalPageTranslation> exact = translations.findByPageIdAndLocale(page.getId(), locale);
        if (exact.isPresent()) {
            return exact;
        }
        // Fall back to the configured site default language (§12.7).
        return translations.findByPageIdAndLocale(page.getId(), fallbackLocale());
    }

    // ---- admin CRUD ----

    @Transactional
    public LegalPage createPage(String slug, String roleMarker, int sortOrder) {
        if (pages.existsBySlug(slug)) {
            throw new ConflictException("A legal page with slug '" + slug + "' already exists");
        }
        LegalPage created = pages.save(new LegalPage(slug, blankToNull(roleMarker), sortOrder));
        log.info("Legal page '{}' created (role marker {}, sort order {})", slug,
                blankToNull(roleMarker) == null ? "none" : roleMarker, sortOrder);
        return created;
    }

    @Transactional
    public LegalPage updatePage(String slug, String roleMarker, int sortOrder) {
        LegalPage page = requirePage(slug);
        page.update(blankToNull(roleMarker), sortOrder);
        log.info("Legal page '{}' updated (role marker {}, sort order {})", slug,
                blankToNull(roleMarker) == null ? "none" : roleMarker, sortOrder);
        return pages.save(page);
    }

    @Transactional
    public void deletePage(String slug) {
        LegalPage page = requirePage(slug);
        log.info("Legal page '{}' deleted with all its translations", slug);
        translations.deleteByPageId(page.getId());
        pages.delete(page);
    }

    /** Creates or replaces a page's translation for a locale. */
    @Transactional
    public void putTranslation(String slug, String locale, String title, String markdownBody) {
        LegalPage page = requirePage(slug);
        translations.findByPageIdAndLocale(page.getId(), locale)
                .ifPresentOrElse(
                        existing -> {
                            existing.update(title, markdownBody);
                            translations.save(existing);
                            log.info("Legal page '{}' [{}] edited: '{}', {} characters", slug, locale, title,
                                    markdownBody == null ? 0 : markdownBody.length());
                        },
                        () -> {
                            translations.save(
                                    new LegalPageTranslation(page.getId(), locale, title, markdownBody));
                            log.info("Legal page '{}' translated to [{}]: '{}'", slug, locale, title);
                        });
    }

    @Transactional
    public void deleteTranslation(String slug, String locale) {
        LegalPage page = requirePage(slug);
        translations.deleteByPageIdAndLocale(page.getId(), locale);
        log.info("Legal page '{}' [{}] translation removed", slug, locale);
    }

    private LegalPage requirePage(String slug) {
        return pages.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Legal page not found: " + slug));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
