// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.legal;

import dev.mosaicast.core.external.translation.TranslationRequest;
import dev.mosaicast.core.external.translation.TranslationResult;
import dev.mosaicast.core.external.translation.TranslationService;
import dev.mosaicast.core.i18n.LocaleRegistry;
import dev.mosaicast.core.web.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Machine-translates a legal page into a <strong>draft</strong> the admin has to save themselves
 * (ARCHITECTURE §12.6/§12.7).
 *
 * <p><strong>It does not write anything.</strong> That is the design, not an omission. §12.6 ships the
 * mechanism and deliberately no legal texts, because a bundled privacy policy nobody read is false safety;
 * a machine translation written straight into the table is the same failure with extra steps. So this
 * returns a draft, the editor shows it unsaved, and a human presses save — or does not.
 *
 * <p>Sent as plain text, never as markdown. A translator does not know that {@code [text](url)} is a link
 * or that a fenced block is code, so both come back mangled; splitting markdown into translatable blocks is
 * a separate project, and until it exists the honest thing is to warn in the UI rather than to pretend.
 */
@Service
public class LegalPrefillService {

    private static final Logger log = LoggerFactory.getLogger(LegalPrefillService.class);

    private final LegalService legal;
    private final TranslationService translation;
    private final LocaleRegistry locales;

    public LegalPrefillService(LegalService legal, TranslationService translation, LocaleRegistry locales) {
        this.legal = legal;
        this.translation = translation;
        this.locales = locales;
    }

    /**
     * An unsaved translation of a page.
     *
     * @param machineTranslated always {@code true} — carried so the UI cannot render this as if a person
     *                          wrote it, and so a future caller storing it has no excuse for losing the fact
     * @param providerId        which service produced it
     * @param sourceLocale      what it was translated from
     */
    public record Draft(String title, String markdown, boolean machineTranslated, String providerId,
                        String sourceLocale) {
    }

    /**
     * Translates an existing translation of a page into another language.
     *
     * @param slug   the page
     * @param target the language to translate into; must be one content may be authored in
     * @param source the language to translate from, or {@code null} for the site default
     * @return the draft, unsaved
     * @throws NotFoundException        if the page has no text in the source language to translate
     * @throws IllegalArgumentException if the target is not a content language, or is the source
     */
    public Draft prefill(String slug, String target, String source) {
        if (!locales.isContentLocale(target)) {
            throw new IllegalArgumentException(
                    "'%s' is not one of this site's content languages".formatted(target));
        }
        String from = source == null || source.isBlank() ? locales.defaultLocale() : source;
        if (from.equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("Source and target language are the same");
        }

        LegalViews.AdminTranslation original = legal.adminList().stream()
                .filter(page -> page.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No legal page '%s'".formatted(slug)))
                .translations().stream()
                .filter(candidate -> candidate.locale().equalsIgnoreCase(from))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Page '%s' has nothing written in '%s' to translate".formatted(slug, from)));

        TranslationResult title =
                translation.translate(TranslationRequest.of(original.title(), from, target));
        String markdown = original.markdown() == null || original.markdown().isBlank()
                ? ""
                : translation.translate(TranslationRequest.of(original.markdown(), from, target)).text();

        // The page is not touched; only that a draft was produced, and by whom.
        log.info("Legal page '{}' drafted into [{}] from [{}] via {}", slug, target, from, title.providerId());
        return new Draft(title.text(), markdown, true, title.providerId(), from);
    }

    /** Whether the button should be offered at all. */
    public boolean available() {
        return translation.available();
    }
}
