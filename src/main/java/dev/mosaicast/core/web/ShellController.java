// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the shell for the host's own public routes with per-URL metadata, structured data and readable
 * content baked in (ARCHITECTURE §6.4/§6.6).
 *
 * <p>Until now only {@code /p/{pluginId}/*} was served this way ({@code PluginPageController}), so a shared
 * plugin deep link previewed correctly while a shared <em>episode</em> link — the thing the BRIEF's
 * Definition of Done actually names — fell through to {@link SpaResourceConfig} and got the bare bundle.
 * These mappings take precedence over that resource handler, exactly as the plugin one already does, and
 * every other path still falls through to it unchanged.
 *
 * <p><strong>An unknown slug is a real 404</strong> (§6.6 — "no soft-404"). The body is still the shell, so
 * a human lands on the app's own not-found view and can navigate on; the <em>status</em> is what a crawler
 * reads, and answering 200 for a page that does not exist is how a site ends up with its 404s indexed.
 * {@link NotFoundException} is thrown by the services underneath, so the rule is enforced at the source
 * rather than re-derived here.
 */
@RestController
public class ShellController {

    private final OgResolver og;
    private final IndexHtmlService indexHtml;
    private final dev.mosaicast.core.i18n.LocaleRegistry locales;

    public ShellController(OgResolver og, IndexHtmlService indexHtml,
                           dev.mosaicast.core.i18n.LocaleRegistry locales) {
        this.og = og;
        this.indexHtml = indexHtml;
        this.locales = locales;
    }

    /** The site root — the "All" tab, with the §6.1 filters applied to the preview it produces. */
    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> home(@RequestParam(required = false) String season,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String order,
                                       @RequestParam(required = false) String lang) {
        String locale = locales.resolveUiLocale(lang);
        return ok(og.home(locale, season, tag, order), locale);
    }

    /** One feed tab. */
    @GetMapping(path = "/feeds/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> feed(@PathVariable String slug,
                                       @RequestParam(required = false) String season,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String order,
                                       @RequestParam(required = false) String lang) {
        String locale = locales.resolveUiLocale(lang);
        return render(() -> og.feed(locale, slug, season, tag, order), locale);
    }

    /**
     * One episode detail page — the link people actually share.
     *
     * <p>{@code t} is the shared position inside the episode (§6.4). It is read here only so the injected
     * {@code og:url} points back at the moment that was sent; the page itself, its canonical URL and its
     * structured data are the episode's either way, and the seek happens in the shell.
     */
    @GetMapping(path = "/episodes/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> episode(@PathVariable String slug,
                                          @RequestParam(required = false) String t,
                                          @RequestParam(required = false) String lang) {
        String locale = locales.resolveUiLocale(lang);
        return render(() -> og.episode(locale, slug, t), locale);
    }

    /**
     * One legal page (§12.6).
     *
     * <p>This used to be served in the site's default locale unconditionally, because locale resolution was
     * client-side — an explicit choice in {@code localStorage}, then the browser — and the server could not
     * see it. That reasoning is what {@code ?lang=} retires: a legal page is the one core surface whose text
     * genuinely differs per language (§12.6), so it is also the one where serving the default to every
     * crawler meant a translated imprint was never indexed at all.
     *
     * <p>The shell still re-renders in the visitor's own locale on mount. What changed is that there is now
     * a URL a crawler can be pointed at which promises a particular language, which is the whole content of
     * an {@code hreflang} alternate.
     */
    @GetMapping(path = "/legal/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> legal(@PathVariable String slug,
                                        @RequestParam(required = false) String lang) {
        String locale = locales.resolveUiLocale(lang);
        return render(() -> og.legal(slug, locale), locale);
    }

    /** Renders a view, turning a missing resource into a real 404 that still carries the shell. */
    private ResponseEntity<String> render(java.util.function.Supplier<PageView> resolve, String locale) {
        try {
            return ok(resolve.get(), locale);
        } catch (NotFoundException e) {
            // The 404 shell is served in the requested language too: a crawler following a dead alternate
            // should still be told what it is looking at, in the language it asked for.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(indexHtml.render(PageView.metaOnly(indexHtml.siteMeta()), locale));
        }
    }

    private ResponseEntity<String> ok(PageView view, String locale) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml.render(view, locale));
    }
}
