// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal-pages mini-CMS (ARCHITECTURE §12.6): admin CRUD over pages and their per-locale markdown, plus
 * the public footer list and rendered page. Bodies are served in the requested locale with fallback to the
 * default locale (§12.7); markdown is rendered sanitized.
 */
@Service
public class LegalService {

    /** English is the source/default locale (§12.7). */
    public static final String DEFAULT_LOCALE = "en";

    private final LegalPageRepository pages;
    private final LegalPageTranslationRepository translations;
    private final MarkdownRenderer markdown;

    public LegalService(LegalPageRepository pages, LegalPageTranslationRepository translations,
                        MarkdownRenderer markdown) {
        this.pages = pages;
        this.translations = translations;
        this.markdown = markdown;
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
        return translations.findByPageIdAndLocale(page.getId(), DEFAULT_LOCALE);
    }

    // ---- admin CRUD ----

    @Transactional
    public LegalPage createPage(String slug, String roleMarker, int sortOrder) {
        if (pages.existsBySlug(slug)) {
            throw new ConflictException("A legal page with slug '" + slug + "' already exists");
        }
        return pages.save(new LegalPage(slug, blankToNull(roleMarker), sortOrder));
    }

    @Transactional
    public LegalPage updatePage(String slug, String roleMarker, int sortOrder) {
        LegalPage page = requirePage(slug);
        page.update(blankToNull(roleMarker), sortOrder);
        return pages.save(page);
    }

    @Transactional
    public void deletePage(String slug) {
        LegalPage page = requirePage(slug);
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
                        },
                        () -> translations.save(
                                new LegalPageTranslation(page.getId(), locale, title, markdownBody)));
    }

    @Transactional
    public void deleteTranslation(String slug, String locale) {
        LegalPage page = requirePage(slug);
        translations.deleteByPageIdAndLocale(page.getId(), locale);
    }

    private LegalPage requirePage(String slug) {
        return pages.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Legal page not found: " + slug));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
