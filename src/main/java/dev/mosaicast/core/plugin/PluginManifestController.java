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
 * the client-facing fields of loaded <em>and activated</em> plugins are exposed — frontend bundle and slots —
 * never backend or source details. Switching a plugin off drops it from this list, so the shell unmounts its
 * elements on the next fetch without waiting for a restart (§7.8).
 */
@RestController
public class PluginManifestController {

    private final PluginLoaderService plugins;

    public PluginManifestController(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/api/plugins/manifest")
    public List<PublicPlugin> manifest() {
        return plugins.allActive().stream()
                .map(PluginRegistration::manifest)
                .map(m -> new PublicPlugin(m.id(), m.name(), m.version(), m.frontend(), m.slots(),
                        !m.schemaEntities().isEmpty()))
                .toList();
    }

    /**
     * The client-facing subset of a plugin manifest.
     *
     * @param hasSchema whether the plugin declares {@code storage.schema}, which is what tells the shell to
     *                  build a {@code ctx.schema} client for it rather than handing it {@code null} — the
     *                  frontend mirror of the Java {@code ctx.schema()} being {@code null} for a doc-store
     *                  plugin. The entity names themselves stay out: the host resolves those per request,
     *                  and a plugin already knows what it declared.
     */
    public record PublicPlugin(String id, String name, String version, Frontend frontend, List<Slot> slots,
                               boolean hasSchema) {
    }
}
