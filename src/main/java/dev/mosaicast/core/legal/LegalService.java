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
import java.util.UUID;
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
        java.util.Map<UUID, List<LegalPageTranslation>> byPage = translationsByPage();
        return pages.findAllByOrderBySortOrderAscSlugAsc().stream()
                .map(page -> new AdminPage(
                        page.getSlug(), page.getRoleMarker(), page.getSortOrder(),
                        byPage.getOrDefault(page.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(LegalPageTranslation::getLocale))
                                .map(tr -> new AdminTranslation(tr.getLocale(), tr.getTitle(), tr.getMarkdown()))
                                .toList()))
                .toList();
    }

    // ---- public read ----

    /**
     * The {@code about} page is authored through this CMS but is not a legal page.
     *
     * It gets the mini-CMS for free — per-locale markdown, sanitising, the tabbed admin editor — without
     * being listed among privacy and imprint, which is a different promise to the reader. `/about` has its
     * own link in the footer and the info menu.
     */
    public static final String ROLE_ABOUT = "about";

    /**
     * Footer links for the locale (pages with no usable translation are omitted).
     *
     * Everything in {@code legal_page} lands here, which is why {@link #ROLE_ABOUT} has to be filtered
     * out explicitly: it lives in the same table on purpose, but grouping it under "Legal" would say
     * something untrue about what it is.
     */
    @Transactional(readOnly = true)
    public List<FooterEntry> footer(String locale) {
        // Two queries, whatever the number of pages. It was up to two per page (the exact locale, then the
        // fallback) on a public path — the consent payload names the privacy page through this (core#195).
        java.util.Map<UUID, List<LegalPageTranslation>> byPage = translationsByPage();
        String fallback = fallbackLocale();
        List<FooterEntry> entries = new ArrayList<>();
        for (LegalPage page : pages.findAllByOrderBySortOrderAscSlugAsc()) {
            if (ROLE_ABOUT.equals(page.getRoleMarker())) {
                continue;
            }
            resolveIn(byPage.getOrDefault(page.getId(), List.of()), locale, fallback).ifPresent(t ->
                    entries.add(new FooterEntry(page.getSlug(), t.getTitle(), page.getRoleMarker())));
        }
        return entries;
    }

    /**
     * The locales a page is <strong>actually</strong> translated into, for {@code hreflang} (§6.6/§12.7).
     *
     * <p>Deliberately not derived from {@link #footer(String)}, which falls back to the site default: that
     * fallback is right for a visitor, who would rather read the imprint in English than see nothing, and
     * wrong for a crawler, which would be told a German version exists and be handed the English text. An
     * alternate is a claim about content, so it is answered from the translation rows and nothing else.
     *
     * @return the locales with a row of their own, ordered; empty when the page has no content at all
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, List<String>> translatedLocalesBySlug() {
        java.util.Map<UUID, List<LegalPageTranslation>> byPage = translationsByPage();
        java.util.Map<String, List<String>> bySlug = new java.util.HashMap<>();
        for (LegalPage page : pages.findAllByOrderBySortOrderAscSlugAsc()) {
            bySlug.put(page.getSlug(), byPage.getOrDefault(page.getId(), List.of()).stream()
                    .map(LegalPageTranslation::getLocale)
                    .filter(locale -> locale != null && !locale.isBlank())
                    .sorted()
                    .toList());
        }
        return bySlug;
    }

    /**
     * The same, for one page.
     *
     * @return the locales with a row of their own, ordered; empty when the page has no content at all
     */
    @Transactional(readOnly = true)
    public List<String> translatedLocales(String slug) {
        return pages.findBySlug(slug)
                .map(page -> translations.findByPageId(page.getId()).stream()
                        .map(LegalPageTranslation::getLocale)
                        .filter(locale -> locale != null && !locale.isBlank())
                        .sorted()
                        .toList())
                .orElseGet(List::of);
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

    /** Every translation, grouped by page: the table is a handful of rows, so one read beats one per page. */
    private java.util.Map<UUID, List<LegalPageTranslation>> translationsByPage() {
        return translations.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(LegalPageTranslation::getPageId));
    }

    /**
     * The translation to show: the exact locale, else the site default, else whichever the page has.
     *
     * <p>The last step is what keeps a page that exists from answering 404. A page written only in German on
     * an English-default site was reachable in the SPA of a German visitor and nowhere else — a hard load
     * resolves the default locale, found nothing, and served the not-found shell for a page that was right
     * there in the admin list (core#164). A reader would rather have the imprint in another language than no
     * imprint; {@link #translatedLocalesBySlug()} still tells crawlers only the truth.
     */
    private static Optional<LegalPageTranslation> resolveIn(
            List<LegalPageTranslation> rows, String locale, String fallback) {
        Optional<LegalPageTranslation> exact = rows.stream()
                .filter(row -> java.util.Objects.equals(row.getLocale(), locale)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        Optional<LegalPageTranslation> siteDefault = rows.stream()
                .filter(row -> java.util.Objects.equals(row.getLocale(), fallback)).findFirst();
        return siteDefault.isPresent() ? siteDefault
                : rows.stream().min(Comparator.comparing(LegalPageTranslation::getLocale));
    }

    private Optional<LegalPageTranslation> resolveTranslation(LegalPage page, String locale) {
        return resolveIn(translations.findByPageId(page.getId()), locale, fallbackLocale());
    }

    // ---- admin CRUD ----

    /**
     * The grammar of a page address: lowercase letters and digits in hyphen-separated runs, like every other
     * slug on the site.
     *
     * <p>The slug becomes {@code /legal/{slug}}, {@code /api/legal/{slug}} and a sitemap entry. It used to be
     * only non-blank, so {@code qa test/2} was accepted and produced a page no link could reach, whose
     * translation saves went nowhere and whose delete answered "No static resource" — removable only in the
     * database (core#164).
     */
    static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Longest accepted slug: an address, not a title. */
    static final int SLUG_MAX = 64;

    /** Refuses a slug outside {@link #SLUG} or longer than {@link #SLUG_MAX}, with a code the form can show. */
    static void requireValidSlug(String slug) {
        if (slug == null || slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
            throw new dev.mosaicast.core.web.CodedBadRequest("legal.slug.invalid",
                    "A page address may use lowercase letters, digits and single hyphens, up to "
                            + SLUG_MAX + " characters.");
        }
    }

    @Transactional
    public LegalPage createPage(String slug, String roleMarker, int sortOrder) {
        requireValidSlug(slug);
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
