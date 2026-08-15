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

    public ShellController(OgResolver og, IndexHtmlService indexHtml) {
        this.og = og;
        this.indexHtml = indexHtml;
    }

    /** The site root — the "All" tab, with the §6.1 filters applied to the preview it produces. */
    @GetMapping(path = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> home(@RequestParam(required = false) String season,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String order) {
        return ok(og.home(season, tag, order));
    }

    /** One feed tab. */
    @GetMapping(path = "/feeds/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> feed(@PathVariable String slug,
                                       @RequestParam(required = false) String season,
                                       @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String order) {
        return render(() -> og.feed(slug, season, tag, order));
    }

    /** One episode detail page — the link people actually share. */
    @GetMapping(path = "/episodes/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> episode(@PathVariable String slug) {
        return render(() -> og.episode(slug));
    }

    /**
     * One legal page (§12.6).
     *
     * <p>Rendered in the site's <em>default</em> locale, not the visitor's: locale resolution is
     * client-side (§12.7 — an explicit choice in {@code localStorage}, then the browser), and the server
     * cannot see that. The shell re-renders in the visitor's locale on mount; a crawler, which is who this
     * block is for, gets the default. That is also the right answer for a crawler, since the default locale
     * is what the canonical URL represents.
     */
    @GetMapping(path = "/legal/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> legal(@PathVariable String slug) {
        return render(() -> og.legal(slug, null));
    }

    /** Renders a view, turning a missing resource into a real 404 that still carries the shell. */
    private ResponseEntity<String> render(java.util.function.Supplier<PageView> resolve) {
        try {
            return ok(resolve.get());
        } catch (NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(indexHtml.render(PageView.metaOnly(indexHtml.siteMeta())));
        }
    }

    private ResponseEntity<String> ok(PageView view) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml.render(view));
    }
}
