// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of every discovered plugin and its load state (ARCHITECTURE §7.8): loaded, or rejected with a
 * reason. Backs the admin warning surface (rendered in the shell in E5b). ADMIN-only via the
 * {@code /api/admin/**} security rule.
 */
@RestController
public class AdminPluginController {

    private final PluginLoaderService plugins;

    public AdminPluginController(PluginLoaderService plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/api/admin/plugins")
    public List<AdminPlugin> list() {
        return plugins.all().stream()
                .map(r -> new AdminPlugin(
                        r.id(),
                        r.status().name(),
                        r.reason(),
                        r.manifest() == null ? null : r.manifest().name(),
                        r.manifest() == null ? null : r.manifest().version()))
                .toList();
    }

    /** One plugin's discovery outcome for the admin surface. */
    public record AdminPlugin(String id, String status, String reason, String name, String version) {
    }
}
