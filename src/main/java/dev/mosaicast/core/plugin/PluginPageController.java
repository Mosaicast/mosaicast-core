// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.web.IndexHtmlService;
import dev.mosaicast.plugin.api.OgMeta;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    public PluginPageController(PluginLoaderService plugins, PluginExtensions extensions,
                                IndexHtmlService indexHtml) {
        this.plugins = plugins;
        this.extensions = extensions;
        this.indexHtml = indexHtml;
    }

    @GetMapping(path = {"/p/{pluginId}", "/p/{pluginId}/**"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String pluginId, HttpServletRequest request) {
        // The status has to match what the shell will actually render: a plugin that is unknown, switched off,
        // or simply declares no `page` slot has no page here, and the shell shows its not-found view. Answering
        // 200 for those would be the soft-404 §6.6 rules out.
        if (!hasPage(pluginId)) {
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
        IndexHtmlService.Meta meta = extensions.shareMetadata(pluginId, subpath)
                .map(PluginPageController::toMeta)
                .orElseGet(indexHtml::siteMeta);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml.render(meta));
    }

    /** Whether an active plugin actually opted into the deep-link page by declaring a {@code page} slot. */
    private boolean hasPage(String pluginId) {
        return plugins.active(pluginId)
                .map(PluginRegistration::manifest)
                .filter(manifest -> manifest.slots() != null)
                .stream()
                .flatMap(manifest -> manifest.slots().stream())
                .anyMatch(slot -> PluginManifest.PLACEMENT_PAGE.equals(slot.placement()));
    }

    /** An {@code OgMeta} with a null image falls back to the host's own default, per the SDK contract. */
    private static IndexHtmlService.Meta toMeta(OgMeta og) {
        return new IndexHtmlService.Meta(
                og.title(), Optional.ofNullable(og.description()).orElse(""), og.imageUrl());
    }

    /** The path below {@code /p/{pluginId}/}; empty at the plugin root. */
    private static String subpathOf(String requestUri, String pluginId) {
        String prefix = "/p/" + pluginId;
        int at = requestUri.indexOf(prefix);
        if (at < 0) {
            return "";
        }
        String rest = requestUri.substring(at + prefix.length());
        return rest.startsWith("/") ? rest.substring(1) : rest;
    }
}
