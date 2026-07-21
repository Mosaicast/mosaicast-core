// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import dev.mosaicast.core.plugin.PluginManifest.Frontend;
import dev.mosaicast.core.plugin.PluginManifest.Slot;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public plugin manifest the shell fetches to mount plugin Web Components (ARCHITECTURE §7.3/§7.5). Only
 * the client-facing fields of loaded plugins are exposed — frontend bundle and slots — never backend or
 * source details. The actual mounting lands with the frontend (E5b).
 */
@RestController
public class PluginManifestController {

    private final PluginLoaderService plugins;

    public PluginManifestController(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/api/plugins/manifest")
    public List<PublicPlugin> manifest() {
        return plugins.all().stream()
                .filter(PluginRegistration::isLoaded)
                .map(PluginRegistration::manifest)
                .map(m -> new PublicPlugin(m.id(), m.name(), m.version(), m.frontend(), m.slots()))
                .toList();
    }

    /** The client-facing subset of a plugin manifest. */
    public record PublicPlugin(String id, String name, String version, Frontend frontend, List<Slot> slots) {
    }
}
