// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import dev.mosaicast.core.i18n.LocaleRegistry;
import dev.mosaicast.core.legal.LegalViews.FooterEntry;
import dev.mosaicast.core.legal.LegalViews.RenderedPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The legal-pages API (ARCHITECTURE §12.6): public footer list + rendered page, and ADMIN-only CRUD (under
 * {@code /api/admin/**}). The locale defaults to English and falls back to it when a translation is missing.
 */
@RestController
public class LegalController {

    private final LegalService legal;
    private final LocaleRegistry locales;
    private final LegalPrefillService prefill;

    public LegalController(LegalService legal, LocaleRegistry locales, LegalPrefillService prefill) {
        this.legal = legal;
        this.locales = locales;
        this.prefill = prefill;
    }

    /**
     * Refuses a locale the admin has not enabled for content (ARCHITECTURE §12.7).
     *
     * <p>Only on the write path. Reads stay tolerant on purpose — {@code resolveTranslation} already falls back
     * to the site default, so a stale bookmark in a language that was switched off should serve the page rather
     * than 404. A <em>write</em> is different: an imprint saved under a locale nobody offers is invisible to
     * every reader and to the editor's own tab strip, which is a page that quietly does not exist.
     */
    private void requireContentLocale(String locale) {
        if (!locales.isContentLocale(locale)) {
            throw new IllegalArgumentException(
                    "'%s' is not one of this site's content languages".formatted(locale));
        }
    }

    /** Create/update a page's metadata. */
    public record PageRequest(@NotBlank String slug, String roleMarker, int sortOrder) {
    }

    /**
     * Create/update a page's body for a locale.
     *
     * <p>Bounded (core#164): the markdown is rendered on every read of a public page and the title lands in
     * the footer, the document title and the sitemap. The caps are far past any real imprint or policy.
     */
    public record TranslationRequest(@NotBlank @Size(max = 200) String title, @Size(max = 200_000) String markdown) {
    }

    // ---- public ----

    @GetMapping("/api/legal")
    public List<FooterEntry> footer(@RequestParam(defaultValue = LegalService.DEFAULT_LOCALE) String locale) {
        return legal.footer(locale);
    }

    @GetMapping("/api/legal/{slug}")
    public RenderedPage page(@PathVariable String slug,
                             @RequestParam(defaultValue = LegalService.DEFAULT_LOCALE) String locale) {
        return legal.render(slug, locale);
    }

    // ---- admin ----

    /** Every page with all locales' raw title + markdown, for the admin editor (§12.6). */
    @GetMapping("/api/admin/legal")
    public java.util.List<LegalViews.AdminPage> adminList() {
        return legal.adminList();
    }

    @PostMapping("/api/admin/legal")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@Valid @RequestBody PageRequest request) {
        legal.createPage(request.slug(), request.roleMarker(), request.sortOrder());
    }

    @PutMapping("/api/admin/legal/{slug}")
    public void update(@PathVariable String slug, @Valid @RequestBody PageRequest request) {
        legal.updatePage(slug, request.roleMarker(), request.sortOrder());
    }

    @DeleteMapping("/api/admin/legal/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String slug) {
        legal.deletePage(slug);
    }

    @PutMapping("/api/admin/legal/{slug}/translations/{locale}")
    public ResponseEntity<Void> putTranslation(
            @PathVariable String slug, @PathVariable String locale, @Valid @RequestBody TranslationRequest request) {
        requireContentLocale(locale);
        legal.putTranslation(slug, locale, request.title(), request.markdown() == null ? "" : request.markdown());
        return ResponseEntity.noContent().build();
    }

    /**
     * Machine-translates a page into a <strong>draft</strong> (§12.6/§12.7).
     *
     * <p>Returns text and writes nothing. The admin edits it and saves through the ordinary
     * {@code PUT} above, or discards it. §12.6 ships the mechanism and no legal texts precisely because a
     * legal page nobody read is false safety — and an automatic one is that with extra steps.
     */
    @PostMapping("/api/admin/legal/{slug}/translations/{locale}/prefill")
    public LegalPrefillService.Draft prefillTranslation(
            @PathVariable String slug, @PathVariable String locale,
            @RequestParam(required = false) String from) {
        return prefill.prefill(slug, locale, from);
    }

    @DeleteMapping("/api/admin/legal/{slug}/translations/{locale}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTranslation(@PathVariable String slug, @PathVariable String locale) {
        legal.deleteTranslation(slug, locale);
    }
}
