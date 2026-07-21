// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Plugin-subsystem configuration (ARCHITECTURE §7.1). Plugins are loaded once at startup from
 * {@code pluginsDir}; each plugin is one folder holding its backend JAR, {@code plugin.json} manifest and
 * {@code assets/} bundle. There is no hot reload.
 *
 * @param pluginsDir filesystem directory scanned for plugin folders (default {@code ./plugins}, overridden
 *                   by {@code MOSAICAST_PLUGINS_DIR})
 */
@ConfigurationProperties(prefix = "mosaicast")
public record PluginProperties(@DefaultValue("./plugins") String pluginsDir) {
}
