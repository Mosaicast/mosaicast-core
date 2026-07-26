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
        if (plugins.active(pluginId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(indexHtml.plain());
        }
        String subpath = subpathOf(request.getRequestURI(), pluginId);
        IndexHtmlService.Meta meta = extensions.shareMetadata(pluginId, subpath)
                .map(PluginPageController::toMeta)
                .orElseGet(indexHtml::siteMeta);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(indexHtml.render(meta));
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
