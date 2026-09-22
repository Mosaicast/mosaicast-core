// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.auth.CurrentUser;
import dev.mosaicast.core.web.IndexHtmlService;
import dev.mosaicast.core.web.PageView;
import dev.mosaicast.plugin.api.OgMeta;
import dev.mosaicast.plugin.api.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves plugin deep links (ARCHITECTURE §6.4). The host reserves {@code /p/{pluginId}/**} so plugin content
 * is linkable and shareable at all; the shell renders the plugin's {@code page} slot and receives the subpath
 * as {@code ctx.route}, while this controller answers the same URL for link scrapers, which run no JS.
 *
 * <p>The plugin's optional {@code ShareMetadataProvider} decides the preview; no provider, no match or a
 * throwing one falls back to site-level OpenGraph. An unknown or switched-off plugin gets a <strong>real
 * 404</strong> (§6.6: no soft-404) while still returning the shell, so the client route renders its own
 * not-found page inside a correct status.
 */
@RestController
public class PluginPageController {

    private final PluginLoaderService plugins;
    private final PluginExtensions extensions;
    private final IndexHtmlService indexHtml;
    private final dev.mosaicast.core.i18n.LocaleRegistry locales;

    public PluginPageController(dev.mosaicast.core.i18n.LocaleRegistry locales,
                                PluginLoaderService plugins, PluginExtensions extensions,
                                IndexHtmlService indexHtml) {
        this.plugins = plugins;
        this.extensions = extensions;
        this.indexHtml = indexHtml;
        this.locales = locales;
    }

    @GetMapping(path = {"/p/{pluginId}", "/p/{pluginId}/**"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String pluginId, HttpServletRequest request,
                                       @org.springframework.web.bind.annotation.RequestParam(
                                               required = false) String lang,
                                       Authentication authentication) {
        String requested = locales.resolveUiLocale(lang);
        Role role = CurrentUser.role(authentication).orElse(null);
        // The status has to match what the shell will actually render: a plugin that is unknown, switched off,
        // or simply declares no `page` slot has no page here, and the shell shows its not-found view. Answering
        // 200 for those would be the soft-404 §6.6 rules out.
        if (!hasPage(pluginId, role)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(indexHtml.plain());
        }
        String subpath = subpathOf(request.getRequestURI(), pluginId);
        // And the same question one level down: the plugin declares a page, but is *this* subpath one?
        // Only the plugin knows, so it is asked (§6.6). A plugin that does not implement the interface
        // answers yes by omission, which is what every plugin written before it did.
        if (!extensions.rendersRoute(pluginId, subpath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(indexHtml.plain());
        }
        Optional<OgMeta> og = extensions.shareMetadata(pluginId, subpath, role);
        IndexHtmlService.Meta meta = og.map(PluginPageController::toMeta).orElseGet(indexHtml::siteMeta);
        // A plugin may state the language its own page is written in, and then that is the answer whoever
        // asked (SDK 0.12.0): a German article stays German for an English visitor, so announcing it as
        // English would be the install-wide `og:locale` bug over again, one level down. Saying nothing —
        // the common case, and the pre-0.12.0 shape — leaves the request's own resolved locale in place.
        String locale = og.map(OgMeta::locale).filter(code -> code != null && !code.isBlank())
                .orElse(requested);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(indexHtml.render(PageView.metaOnly(meta), locale));
    }

    /**
     * Whether an active plugin opted into the deep-link page by declaring a {@code page} slot
     * <em>this caller may be shown</em>.
     *
     * <p>The {@code visibleTo} floor was a browser-side decision: the shell hid a podcaster-only page from
     * a fan's menu while this route still answered 200 for anyone, rendered the shell, and — through
     * {@code ShareMetadataProvider} — put the page's own title and description into the response for an
     * anonymous crawler. Hiding an entrance in the UI and serving it here are not the same claim, and only
     * one of them is enforcement.
     *
     * <p>A caller below the floor gets the same 404 as an unknown plugin. That is deliberate: a distinct
     * 403 would tell them the page exists, which is precisely what {@code visibleTo} says not to.
     */
    private boolean hasPage(String pluginId, Role role) {
        int rank = PluginAccessPolicy.rankOf(Optional.ofNullable(role));
        return plugins.active(pluginId)
                .map(PluginRegistration::manifest)
                .filter(manifest -> manifest.slots() != null)
                .stream()
                .flatMap(manifest -> manifest.slots().stream())
                .filter(slot -> PluginManifest.PLACEMENT_PAGE.equals(slot.placement()))
                .anyMatch(slot -> rank >= PluginAccessPolicy.visibilityFloorOf(slot.visibleTo()));
    }

    /** An {@code OgMeta} with a null image falls back to the host's own default, per the SDK contract. */
    private static IndexHtmlService.Meta toMeta(OgMeta og) {
        return new IndexHtmlService.Meta(
                og.title(), Optional.ofNullable(og.description()).orElse(""), og.imageUrl());
    }

    /**
     * The path below {@code /p/{pluginId}/}; empty at the plugin root.
     *
     * <p><strong>Decoded.</strong> {@code getRequestURI()} is, per the servlet spec, not decoded, so a
     * plugin's own extension points saw {@code caf%C3%A9-episode} where the browser hands the same plugin
     * {@code café-episode} through {@code ctx.route}. For the wiki, whose slugs come from page titles, that
     * meant a deep link to any page with a non-ASCII character 404'd server-side while working perfectly
     * once the SPA had booted — the plugin was being asked about a path that does not exist in its world.
     *
     * <p>Decoded per segment rather than all at once, so the host never re-splits on a slash it produced
     * itself. That is as far as it goes: the subpath reaches the plugin as one flat string, so {@code a%2Fb}
     * and {@code a/b} arrive identical and a plugin splitting on {@code /} cannot tell them apart. Carrying
     * that difference would take a shape change on the SDK side, and no plugin has needed it.
     */
    static String subpathOf(String requestUri, String pluginId) {
        String prefix = "/p/" + pluginId;
        int at = requestUri.indexOf(prefix);
        if (at < 0) {
            return "";
        }
        String rest = requestUri.substring(at + prefix.length());
        String raw = rest.startsWith("/") ? rest.substring(1) : rest;
        if (raw.isEmpty()) {
            return "";
        }
        return java.util.Arrays.stream(raw.split("/", -1))
                .map(segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("/"));
    }
}
