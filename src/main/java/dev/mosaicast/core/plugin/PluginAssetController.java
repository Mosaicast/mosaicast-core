// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.web.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * Serves a loaded plugin's frontend bundle from its {@code assets/} folder (ARCHITECTURE §7.5). Public with
 * an ETag from the file's size + last-modified (revalidate-always, like branding §12.2), so a rebuilt bundle
 * propagates immediately. Lives outside {@code /api} so it is anonymous by default. Path traversal outside
 * the plugin's {@code assets/} is refused.
 */
@RestController
public class PluginAssetController {

    private final PluginLoaderService plugins;

    public PluginAssetController(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/plugins/{id}/assets/{*path}")
    public ResponseEntity<byte[]> serve(@PathVariable String id, @PathVariable String path, WebRequest request) {
        // Active, not merely loaded: a switched-off plugin serves no bundle either (§7.8).
        PluginRegistration plugin = plugins.active(id)
                .orElseThrow(() -> new NotFoundException("Unknown plugin: " + id));
        Path file = resolveAsset(plugin.directory(), path);

        String etag = etagOf(file);
        if (request.checkNotModified(etag)) {
            return null;
        }
        try {
            return ResponseEntity.ok()
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache())
                    .contentType(contentType(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new NotFoundException("Cannot read asset: " + path);
        }
    }

    /** Resolves {@code path} under the plugin's {@code assets/}, refusing any escape from that directory. */
    private static Path resolveAsset(Path pluginDir, String path) {
        Path assets = pluginDir.resolve("assets").normalize();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path target = assets.resolve(relative).normalize();
        if (!target.startsWith(assets) || !Files.isRegularFile(target)) {
            throw new NotFoundException("Asset not found: " + path);
        }
        return target;
    }

    private static String etagOf(Path file) {
        try {
            return "\"" + Files.size(file) + "-" + Files.getLastModifiedTime(file).toMillis() + "\"";
        } catch (IOException e) {
            throw new NotFoundException("Cannot stat asset: " + file.getFileName());
        }
    }

    private static MediaType contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".js") || name.endsWith(".mjs")) {
            return MediaType.parseMediaType("text/javascript");
        }
        if (name.endsWith(".css")) {
            return MediaType.parseMediaType("text/css");
        }
        if (name.endsWith(".json") || name.endsWith(".map")) {
            return MediaType.APPLICATION_JSON;
        }
        try {
            String probed = Files.probeContentType(file);
            return probed != null ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IOException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
