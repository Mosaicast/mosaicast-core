// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.i18n;

import dev.mosaicast.core.i18n.LocaleViews.AdminLocales;
import dev.mosaicast.core.i18n.LocaleViews.PublicLocales;
import dev.mosaicast.core.i18n.LocaleViews.UpdateLocales;
import dev.mosaicast.core.web.NotFoundException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * The language API (ARCHITECTURE §12.7): the public list and catalogs the shell loads at runtime, and the
 * ADMIN-only languages page underneath {@code /api/admin/**}.
 */
@RestController
public class LocaleController {

    private final LocaleService locales;
    private final LocaleRegistry registry;

    public LocaleController(LocaleService locales, LocaleRegistry registry) {
        this.locales = locales;
        this.registry = registry;
    }

    // ---- public ----

    /** Which languages exist, which the shell offers, and which content may be authored in. */
    @GetMapping("/api/i18n/locales")
    public PublicLocales list() {
        return locales.publicLocales();
    }

    /**
     * One language's messages, for the shell to register at runtime.
     *
     * <p>Etagged and cached: a catalog changes when an operator edits a file, and until then this is the same
     * few dozen kilobytes on every cold load in that language.
     *
     * <p>Only languages the admin actually offers are served. A catalog sitting in the drop-in directory that
     * nobody enabled is not a 404 by accident — serving it would let the shell render a language the operator
     * has not published, and the same list feeds the language switcher.
     */
    @GetMapping("/api/i18n/catalog/{code}")
    public ResponseEntity<Map<String, String>> catalog(@PathVariable String code, WebRequest request) {
        if (!registry.isUiLocale(code)) {
            throw new NotFoundException("No catalog for language '%s'".formatted(code));
        }
        LocaleCatalog catalog = registry.catalog(code)
                .orElseThrow(() -> new NotFoundException("No catalog for language '%s'".formatted(code)));
        String etag = "\"%s\"".formatted(Integer.toHexString(catalog.messages().hashCode())
                + "-" + catalog.messages().size());
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(catalog.messages());
    }

    // ---- admin ----

    /** Every detected language with its origin and translation debt, plus where to drop new files. */
    @GetMapping("/api/admin/i18n")
    public AdminLocales adminList() {
        return locales.adminLocales();
    }

    /** Replaces the language policy: shell languages, content languages, default. */
    @PutMapping("/api/admin/i18n")
    public AdminLocales update(@RequestBody UpdateLocales request) {
        return locales.update(request.uiLocales(), request.contentLocales(), request.defaultLocale());
    }
}
